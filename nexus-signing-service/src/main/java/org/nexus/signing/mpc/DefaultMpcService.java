package org.nexus.signing.mpc;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.nexus.signing.mpc.barrier.RoundBarrier;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.MpcCryptoEngine;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.nexus.signing.mpc.persistence.MpcKeyShareStore;
import org.nexus.signing.mpc.persistence.MpcSessionRepository;
import org.nexus.signing.mpc.persistence.MpcWalletRepository;
import org.nexus.signing.mpc.router.MessageRouter;
import org.nexus.signing.mpc.transport.MpcMessage;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MPC 编排层默认实现，通过 {@link MpcCryptoEngine} SPI 调用底层密码学引擎。
 *
 * <p>本类负责 <b>编排</b>（会话生命周期、持久化、传输、轮次同步、签名验证），
 * 不实现任何密码学原语。密码学由注入的 {@link MpcCryptoEngine} 完成
 * （参考实现 {@code GrpcMpcCryptoEngine} 通过 gRPC 调用 Rust multi-party-ecdsa 引擎，
 * 审计报告 §4.1 方案 A）。</p>
 *
 * <h2>三个核心编排方法</h2>
 * <ul>
 *   <li>{@link #generateKeyShare} — DKG 编排：创建 session → 调 {@code engine.dkg} → 存储 keyShare</li>
 *   <li>{@link #sign} — 签名编排：创建 session → 加载 keyShare → 调 {@code engine.sign} → 广播部分签名 → barrier 同步</li>
 *   <li>{@link #aggregateSignature} — 聚合编排：调 {@code engine.aggregate} → ECDSA 验证 → 广播最终签名</li>
 * </ul>
 *
 * <h2>遗留接口方法</h2>
 * <p>{@link MpcService} 接口的 {@link #createMpcWallet} / {@link #signTransaction} /
 * {@link #rotateKey} 因缺少必要的编排参数（partyIndex、peerEndpoints 等）保留为
 * 遗留 stub，调用方应直接使用上述三个编排方法。</p>
 *
 * <h2>线程安全</h2>
 * <p>本类线程安全：{@link MpcCryptoEngine} 与各 repository 实现线程安全，
 * {@link #sessionBarriers} 用 {@link ConcurrentHashMap} 保护。</p>
 *
 * @see MpcCryptoEngine
 * @see GrpcMpcCryptoEngine
 */
@Service
public class DefaultMpcService implements MpcService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMpcService.class);

    /** 默认椭圆曲线（比特币 / 以太坊 ECDSA）。 */
    private static final String DEFAULT_CURVE = "secp256k1";

    /** 编排层视角的签名广播轮次号（引擎内部的 7 轮对编排层不可见）。 */
    private static final int SIGN_BROADCAST_ROUND = 1;

    private final MpcCryptoEngine mpcCryptoEngine;
    private final MpcSessionRepository sessionRepository;
    private final MpcWalletRepository walletRepository;
    private final MpcKeyShareStore keyShareStore;
    private final MpcTransport transport;
    private final MessageRouter messageRouter;

    /** sessionId → RoundBarrier，用于单 JVM 多参与方测试的轮次同步。 */
    private final ConcurrentHashMap<String, RoundBarrier> sessionBarriers = new ConcurrentHashMap<>();

    /**
     * 构造编排服务，注入所有编排依赖。
     *
     * @param mpcCryptoEngine  MPC 密码学引擎 SPI（gRPC → Rust 引擎）
     * @param sessionRepository 签名会话持久化
     * @param walletRepository  钱包元数据持久化
     * @param keyShareStore     密钥份额加密本地存储
     * @param transport         P2P 传输层（接收对端部分签名）
     * @param messageRouter     消息路由器（广播 + WAL + 去重）
     */
    public DefaultMpcService(MpcCryptoEngine mpcCryptoEngine,
                             MpcSessionRepository sessionRepository,
                             MpcWalletRepository walletRepository,
                             MpcKeyShareStore keyShareStore,
                             MpcTransport transport,
                             MessageRouter messageRouter) {
        this.mpcCryptoEngine = Objects.requireNonNull(mpcCryptoEngine, "mpcCryptoEngine");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository");
        this.keyShareStore = Objects.requireNonNull(keyShareStore, "keyShareStore");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.messageRouter = Objects.requireNonNull(messageRouter, "messageRouter");
    }

    // =========================================================================
    // generateKeyShare：DKG 编排
    // =========================================================================

    /**
     * 编排分布式密钥生成（DKG）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>创建并持久化 DKG 审计 session</li>
     *   <li>构造 {@link DkgRequest} 并调用 {@link MpcCryptoEngine#dkg}</li>
     *   <li>检查响应，失败抛 {@link MpcProtocolException}</li>
     *   <li>创建 {@link MpcKeyShare} 并加密存储到 {@link MpcKeyShareStore}</li>
     *   <li>更新 session 状态为 COMPLETED</li>
     *   <li>返回 {@link MpcKeyGeneration.DkgResult}（聚合公钥 + 本节点份额）</li>
     * </ol>
     *
     * @param sessionId     全局唯一会话 ID（编排层生成，跨所有参与方一致）
     * @param threshold     阈值 t（t-of-n）
     * @param totalParties  总参与方数 n
     * @param partyIndex    本节点索引（0-based）
     * @param participantId 本节点参与者 ID
     * @param curve         椭圆曲线名称（null 则用 secp256k1）
     * @param participants  所有参与方列表（含本节点，用于提取对端端点）
     * @return DKG 结果（聚合公钥 + 本节点密钥份额）
     * @throws MpcProtocolException 若引擎调用失败或参数非法
     */
    public MpcKeyGeneration.DkgResult generateKeyShare(
            String sessionId,
            int threshold,
            int totalParties,
            int partyIndex,
            String participantId,
            String curve,
            List<MpcParticipant> participants) {

        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(participants, "participants");
        String effectiveCurve = (curve == null || curve.isEmpty()) ? DEFAULT_CURVE : curve;

        log.info("DKG orchestration start: session={}, t={}, n={}, partyIndex={}, participant={}",
                sessionId, threshold, totalParties, partyIndex, participantId);

        // 1. 创建并持久化 DKG 审计 session（复用 MpcSignSession 作为通用会话记录）
        MpcSignSession session = new MpcSignSession();
        session.setSessionId(sessionId);
        session.setWalletId("DKG-" + sessionId);
        session.setTxData("DKG");
        session.setStatus(MpcSignSession.SessionStatus.PENDING);
        session.setCreatedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 2. 构造 DkgRequest（提取对端端点，排除本节点）
        List<String> peerEndpoints = participants.stream()
                .filter(p -> !p.getParticipantId().equals(participantId))
                .map(MpcParticipant::getEndpoint)
                .collect(Collectors.toList());
        DkgRequest request = new DkgRequest(sessionId, threshold, totalParties, partyIndex,
                effectiveCurve, peerEndpoints);

        // 3. 调用密码学引擎
        DkgResponse response = mpcCryptoEngine.dkg(request);
        if (!response.isSuccess()) {
            session.setStatus(MpcSignSession.SessionStatus.FAILED);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                    "DKG engine failed: " + response.getError());
        }

        // 4. 创建并加密存储本节点密钥份额
        //    publicShareHex 暂用聚合公钥（DkgResponse 未返回 per-participant publicShare）
        MpcKeyShare keyShare = new MpcKeyShare(
                participantId,
                response.getKeyShare(),
                response.getPublicKey(),
                null);
        keyShareStore.save(keyShare);

        // 5. 更新 session 状态为 COMPLETED
        session.setStatus(MpcSignSession.SessionStatus.COMPLETED);
        session.setCombinedSignature(response.getPublicKey());
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        log.info("DKG orchestration complete: session={}, publicKey={}",
                sessionId, response.getPublicKey());

        // 6. 返回 DkgResult
        return new MpcKeyGeneration.DkgResult(
                response.getPublicKey(),
                List.of(keyShare),
                LocalDateTime.now());
    }

    // =========================================================================
    // sign：签名编排
    // =========================================================================

    /**
     * 编排分布式签名的本节点部分（partial signature）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>创建并持久化签名 session</li>
     *   <li>从 {@link MpcKeyShareStore} 加载本节点密钥份额</li>
     *   <li>连接传输层（若未连接）</li>
     *   <li>构造 {@link SignRequest} 并调用 {@link MpcCryptoEngine#sign}</li>
     *   <li>检查响应，失败抛 {@link MpcProtocolException}</li>
     *   <li>通过 {@link MessageRouter} 广播部分签名给其他参与方</li>
     *   <li>{@link RoundBarrier} 同步：arrive + awaitRound</li>
     *   <li>通过 {@link MpcTransport#receive} 收集对端部分签名到 session</li>
     *   <li>持久化 session 并返回本节点部分签名</li>
     * </ol>
     *
     * @param sessionId         全局会话 ID
     * @param walletId          钱包 ID
     * @param publicKey         聚合公钥（hex）
     * @param messageHash       待签名消息哈希（hex，32 字节）
     * @param partyIndex        本节点索引
     * @param participantId     本节点参与者 ID
     * @param participants      所有参与方列表（含本节点）
     * @param threshold         阈值 t
     * @param roundTimeoutMillis 轮次同步与接收超时（毫秒）
     * @return 本节点部分签名（hex）
     * @throws MpcProtocolException 若引擎调用失败、份额缺失或轮次超时
     */
    public String sign(
            String sessionId,
            String walletId,
            String publicKey,
            String messageHash,
            int partyIndex,
            String participantId,
            List<MpcParticipant> participants,
            int threshold,
            long roundTimeoutMillis) {

        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(messageHash, "messageHash");
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(participants, "participants");

        log.info("Sign orchestration start: session={}, wallet={}, participant={}",
                sessionId, walletId, participantId);

        // 1. 创建并持久化签名 session
        MpcSignSession session = new MpcSignSession();
        session.setSessionId(sessionId);
        session.setWalletId(walletId);
        session.setTxData(messageHash);
        session.setStatus(MpcSignSession.SessionStatus.COLLECTING);
        session.setCreatedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 2. 加载本节点密钥份额
        MpcKeyShare keyShare = keyShareStore.load(participantId)
                .orElseThrow(() -> new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_STATE,
                        "key share not found for participant " + participantId));

        // 3. 连接传输层（若未连接）
        if (!transport.isConnected()) {
            transport.connect(participants);
        }

        // 4. 构造 SignRequest
        List<String> peerEndpoints = participants.stream()
                .filter(p -> !p.getParticipantId().equals(participantId))
                .map(MpcParticipant::getEndpoint)
                .collect(Collectors.toList());
        SignRequest request = new SignRequest(sessionId, publicKey,
                keyShare.getPrivateShareHex(), messageHash, partyIndex, peerEndpoints);

        // 5. 调用密码学引擎（引擎内部通过 peerEndpoints 完成 7 轮 P2P 消息交换）
        SignResponse response = mpcCryptoEngine.sign(request);
        if (!response.isSuccess()) {
            session.setStatus(MpcSignSession.SessionStatus.FAILED);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.INVALID_SHARE,
                    "Sign engine failed: " + response.getError());
        }
        String partialSignature = response.getPartialSignature();

        // 6. 广播本节点部分签名给其他参与方
        MpcMessage broadcast = MpcMessage.create(sessionId, SIGN_BROADCAST_ROUND,
                MpcMessage.Type.SIGN_ROUND, participantId, null, partialSignature);
        try {
            messageRouter.broadcast(broadcast);
        } catch (Exception e) {
            log.warn("Broadcast partial signature failed (will rely on aggregator collection): {}",
                    e.getMessage());
        }

        // 7. RoundBarrier 同步（单 JVM 多参与方测试场景）
        RoundBarrier barrier = sessionBarriers.computeIfAbsent(sessionId,
                id -> new RoundBarrier(id, threshold));
        barrier.arrive(SIGN_BROADCAST_ROUND, participantId);
        try {
            barrier.awaitRound(SIGN_BROADCAST_ROUND, roundTimeoutMillis);
        } catch (MpcProtocolException e) {
            log.warn("Barrier await timed out, proceeding with available shares: {}", e.getMessage());
        }

        // 8. 收集对端部分签名（多 JVM 生产场景通过 transport.receive）
        collectPeerPartialSignatures(sessionId, participantId, participants,
                roundTimeoutMillis, session);

        // 9. 记录本节点部分签名到 session
        session.getSignatureShares().put(participantId, partialSignature);
        session.getSignedParticipants().add(participantId);
        sessionRepository.save(session);

        log.info("Sign orchestration complete: session={}, participant={}, collectedShares={}",
                sessionId, participantId, session.getSignatureShares().size());
        return partialSignature;
    }

    /**
     * 从对端接收部分签名并记录到 session。
     *
     * @param sessionId     会话 ID
     * @param participantId 本节点 ID（排除）
     * @param participants  所有参与方
     * @param timeoutMillis 接收超时
     * @param session       待填充的 session
     */
    private void collectPeerPartialSignatures(String sessionId,
                                              String participantId,
                                              List<MpcParticipant> participants,
                                              long timeoutMillis,
                                              MpcSignSession session) {
        for (MpcParticipant peer : participants) {
            if (peer.getParticipantId().equals(participantId)) {
                continue;
            }
            try {
                MpcMessage msg = transport.receive(sessionId, SIGN_BROADCAST_ROUND,
                        peer.getParticipantId(), timeoutMillis);
                if (messageRouter.receive(msg)) {
                    session.getSignatureShares().put(peer.getParticipantId(), msg.getPayloadHex());
                    session.getSignedParticipants().add(peer.getParticipantId());
                }
            } catch (Exception e) {
                log.warn("Failed to receive partial signature from {}: {}",
                        peer.getParticipantId(), e.getMessage());
            }
        }
    }

    // =========================================================================
    // aggregateSignature：聚合编排
    // =========================================================================

    /**
     * 编排签名聚合：收集 t 个部分签名 → 调引擎聚合 → ECDSA 验证 → 广播。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>加载 session；若 partialSignatures 为空则从 session 读取</li>
     *   <li>构造 {@link AggregateRequest} 并调用 {@link MpcCryptoEngine#aggregate}</li>
     *   <li>检查响应，失败抛 {@link MpcProtocolException}</li>
     *   <li>ECDSA 验证：(r, s) 对应 publicKey 和 messageHash（secp256k1，BouncyCastle）</li>
     *   <li>更新 session 状态为 COMPLETED，记录最终签名</li>
     *   <li>广播最终签名给所有参与方</li>
     *   <li>返回 {@link AggregateResponse}（含 r, s, recoveryId）</li>
     * </ol>
     *
     * @param sessionId         会话 ID
     * @param walletId          钱包 ID（用于加载 session）
     * @param publicKey         聚合公钥（hex）
     * @param messageHash       消息哈希（hex）
     * @param partialSignatures 部分签名列表（至少 t 个）；为空则从 session 读取
     * @return 聚合响应（含最终签名 r, s, recoveryId）
     * @throws MpcProtocolException 若引擎调用失败、份额不足或 ECDSA 验证失败
     */
    public AggregateResponse aggregateSignature(
            String sessionId,
            String walletId,
            String publicKey,
            String messageHash,
            List<String> partialSignatures) {

        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(publicKey, "publicKey");
        Objects.requireNonNull(messageHash, "messageHash");

        log.info("Aggregate orchestration start: session={}, wallet={}, providedShares={}",
                sessionId, walletId,
                partialSignatures == null ? 0 : partialSignatures.size());

        // 1. 加载 session（若存在）
        Optional<MpcSignSession> sessionOpt = sessionRepository.findById(sessionId);
        MpcSignSession session = sessionOpt.orElseGet(() -> {
            MpcSignSession s = new MpcSignSession();
            s.setSessionId(sessionId);
            s.setWalletId(walletId);
            s.setTxData(messageHash);
            s.setCreatedAt(LocalDateTime.now());
            return s;
        });

        // 2. 确定部分签名列表：优先用传入参数，为空则从 session 读取
        List<String> shares = partialSignatures;
        if ((shares == null || shares.isEmpty()) && session.getSignatureShares() != null) {
            shares = new ArrayList<>(session.getSignatureShares().values());
        }
        if (shares == null || shares.isEmpty()) {
            session.setStatus(MpcSignSession.SessionStatus.FAILED);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no partial signatures to aggregate for session " + sessionId);
        }

        // 3. 构造 AggregateRequest 并调用引擎
        AggregateRequest request = new AggregateRequest(sessionId, publicKey, messageHash, shares);
        AggregateResponse response = mpcCryptoEngine.aggregate(request);
        if (!response.isSuccess()) {
            session.setStatus(MpcSignSession.SessionStatus.FAILED);
            session.setCompletedAt(LocalDateTime.now());
            sessionRepository.save(session);
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                    "Aggregate engine failed: " + response.getError());
        }

        // 4. ECDSA 验证：(r, s) 对应 publicKey 和 messageHash
        verifyEcdsaSignature(publicKey, messageHash, response.getR(), response.getS());

        // 5. 更新 session 状态为 COMPLETED
        session.setStatus(MpcSignSession.SessionStatus.COMPLETED);
        session.setCombinedSignature(response.getSignature());
        session.setCompletedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 6. 广播最终签名给所有参与方（用于审计与对端确认）
        try {
            MpcMessage broadcast = MpcMessage.create(sessionId, SIGN_BROADCAST_ROUND,
                    MpcMessage.Type.AGGREGATE_ROUND, "aggregator", null,
                    response.getSignature());
            messageRouter.broadcast(broadcast);
        } catch (Exception e) {
            log.warn("Broadcast aggregated signature failed: {}", e.getMessage());
        }

        log.info("Aggregate orchestration complete: session={}, recoveryId={}",
                sessionId, response.getRecoveryId());
        return response;
    }

    // =========================================================================
    // ECDSA 签名验证（BouncyCastle，secp256k1）
    // =========================================================================

    /**
     * 验证 ECDSA 签名 (r, s) 对应公钥 publicKeyHex 和消息哈希 messageHashHex。
     *
     * <p>验证等式：令 u1 = z * s^-1 mod n, u2 = r * s^-1 mod n,
     * R = u1*G + u2*Q，则 R.x mod n == r。</p>
     *
     * @param publicKeyHex   公钥曲线点（hex，04||x||y 未压缩或压缩格式）
     * @param messageHashHex 消息哈希（hex，32 字节）
     * @param rHex           签名 r（hex）
     * @param sHex           签名 s（hex）
     * @throws MpcProtocolException 若验证失败（{@link MpcProtocolException.Reason#SHARE_VERIFICATION_FAILED}）
     */
    private void verifyEcdsaSignature(String publicKeyHex,
                                      String messageHashHex,
                                      String rHex,
                                      String sHex) {
        try {
            X9ECParameters params = ECNamedCurveTable.getByName(DEFAULT_CURVE);
            if (params == null) {
                throw new IllegalStateException("curve " + DEFAULT_CURVE + " not available in BouncyCastle");
            }
            ECPoint G = params.getG();
            BigInteger n = params.getN();

            // 解析公钥点
            byte[] pubBytes = HexFormat.of().parseHex(publicKeyHex);
            ECPoint Q = params.getCurve().decodePoint(pubBytes);
            // MPC-P0 修复：decodePoint 后必须校验点在曲线上，防止 Invalid Curve Attack。
            // 攻击者可构造不在 secp256k1 上的点绕过签名验证；isValid() 内部检查 y² = x³ + ax + b。
            if (!Q.isValid()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "ECDSA verify failed: public key point is not on the curve "
                                + "(Invalid Curve Attack defense, MPC-P0)");
            }
            if (Q.isInfinity()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "ECDSA verify failed: public key point at infinity");
            }

            // 解析 r, s, z（消息哈希截断到曲线阶 n 的比特长度）
            BigInteger r = new BigInteger(1, HexFormat.of().parseHex(rHex));
            BigInteger s = new BigInteger(1, HexFormat.of().parseHex(sHex));
            BigInteger z = new BigInteger(1, HexFormat.of().parseHex(messageHashHex));

            // 范围检查
            if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(n) >= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "ECDSA verify failed: r out of range [1, n-1]");
            }
            if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(n) >= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "ECDSA verify failed: s out of range [1, n-1]");
            }

            // u1 = z * s^-1 mod n, u2 = r * s^-1 mod n
            BigInteger sInv = s.modInverse(n);
            BigInteger u1 = z.multiply(sInv).mod(n);
            BigInteger u2 = r.multiply(sInv).mod(n);

            // R = u1*G + u2*Q
            ECPoint R = ECAlgorithms.sumOfTwoMultiplies(G, u1, Q, u2).normalize();
            if (R.isInfinity()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "ECDSA verify failed: computed R is point at infinity");
            }

            // 验证 R.x mod n == r
            BigInteger rPrime = R.getAffineXCoord().toBigInteger().mod(n);
            if (!rPrime.equals(r)) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "ECDSA verify failed: r' (" + rPrime + ") != r (" + r + ")");
            }

            log.debug("ECDSA verification passed for publicKey={}", publicKeyHex);
        } catch (MpcProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                    "ECDSA verification error: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // 遗留接口方法（MpcService）
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * @deprecated 遗留 stub。真实 DKG 编排请使用
     *             {@link #generateKeyShare(String, int, int, int, String, String, List)}。
     */
    @Override
    @Deprecated
    public MpcWallet createMpcWallet(List<String> participants, int threshold) {
        log.warn("createMpcWallet legacy stub — use generateKeyShare for real DKG; "
                        + "participants={}, threshold={}", participants, threshold);
        MpcWallet stub = new MpcWallet();
        stub.setParticipants(participants);
        stub.setThreshold(threshold);
        return stub;
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated 遗留 stub。真实 MPC 签名请使用
     *             {@link #sign} + {@link #aggregateSignature}。
     */
    @Override
    @Deprecated
    public String signTransaction(String walletId, String txData) {
        // legacy stub 真实化：查钱包 → 本节点 sign → aggregateSignature（真实 MPC 编排）
        try {
            if (walletId == null || walletId.isEmpty() || txData == null || txData.isEmpty()) {
                log.warn("signTransaction: walletId/txData required (walletId={})", walletId);
                return null;
            }
            var walletOpt = walletRepository.findById(walletId);
            if (walletOpt.isEmpty()) {
                log.warn("signTransaction: wallet {} not found", walletId);
                return null;
            }
            MpcWallet wallet = walletOpt.get();
            String publicKey = wallet.getPublicKey();
            if (publicKey == null || publicKey.isEmpty()) {
                log.warn("signTransaction: wallet {} has no public key", walletId);
                return null;
            }
            // 签名编排：本节点份额 sign → 聚合（单节点协调器模型下聚合即最终签名）
            String sessionId = "tx-" + walletId + "-" + System.currentTimeMillis();
            // sign 所需参数：从钱包参与者推断（简化：本节点为 party 0）
            String participantId = wallet.getParticipants().isEmpty()
                    ? "local" : wallet.getParticipants().get(0);
            List<MpcParticipant> participants = new java.util.ArrayList<>();
            participants.add(new MpcParticipant(participantId, "localhost", "", true));
            String partial = sign(sessionId, walletId, publicKey, txData,
                    0, participantId, participants,
                    wallet.getThreshold() == null ? 1 : wallet.getThreshold(), 30_000L);
            if (partial == null) {
                log.warn("signTransaction: sign produced null for wallet={}", walletId);
                return null;
            }
            AggregateResponse agg = aggregateSignature(sessionId, walletId, publicKey,
                    txData, java.util.List.of(partial));
            String signature = agg.getSignature() == null ? null : agg.getSignature();
            log.info("signTransaction (real MPC): wallet={} signed={}",
                    walletId, signature != null);
            return signature;
        } catch (Exception e) {
            log.error("signTransaction failed for wallet={}: {}", walletId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated 遗留 stub。密钥轮换待后续任务实现。
     */
    @Override
    @Deprecated
    public MpcWallet rotateKey(String walletId) {
        log.warn("rotateKey legacy stub — walletId={}", walletId);
        return null;
    }
}
