package org.nexus.signing.mpc;

import com.google.gson.JsonObject;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.mpc.cggmp.CggmpMpcCryptoEngine;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Cold-wallet multi-sig transfer service orchestrating the full MPC signing
 * flow for cold-wallet withdrawals.
 *
 * <p>GG20 退役后（PLAN-001-R2，2026-10-08）：真实 MPC 引擎只有
 * {@link CggmpMpcCryptoEngine} 一条路径——{@code mpc.engine.cggmp-enabled}
 * 不再选择"用哪条路径"，而是"是否启用真实引擎"（false 时降级为
 * FROZEN skeleton 记账流程，供无集群的本地/沙箱环境使用）。</p>
 */
@Service
public class ColdWalletMultiSigService {

    private static final Logger log = LoggerFactory.getLogger(ColdWalletMultiSigService.class);

    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(5);

    private final Map<String, MpcSigningSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, TransferContext> transferContexts = new ConcurrentHashMap<>();
    private final Map<String, MpcWallet> wallets = new ConcurrentHashMap<>();
    private final Map<String, List<MpcKeyShare>> keyShares = new ConcurrentHashMap<>();

    private final MpcSigner signer;
    private final MpcSignatureAggregator aggregator;
    private final MpcApprovalPolicy approvalPolicy;
    private final NodeController nodeController;

    /** CGGMP21 路径引擎（唯一真实 MPC 引擎；GG20 退役后无第二实现）。 */
    @Autowired(required = false)
    private CggmpMpcCryptoEngine cggmpEngine;

    @Autowired
    public ColdWalletMultiSigService(MpcSigner signer,
                                     MpcSignatureAggregator aggregator,
                                     @Qualifier("mpcApprovalPolicy") MpcApprovalPolicy approvalPolicy,
                                     NodeController nodeController) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.nodeController = Objects.requireNonNull(nodeController, "nodeController");
        log.info("ColdWalletMultiSigService initialised (CGGMP21 single-engine path)");
    }

    /**
     * 选择当前真实 MPC 引擎（GG20 退役后唯一候选：CGGMP21）。
     *
     * <ol>
     *   <li>{@link CggmpMpcCryptoEngine#isCggmpEnabled()} true 且注入可用
     *       且 healthCheck 通过 → 返回该引擎</li>
     *   <li>否则返回 null（走 FROZEN skeleton 记账流程——fail-closed：
     *       集群不可用时绝不伪造真实签名）</li>
     * </ol>
     */
    private CggmpMpcCryptoEngine selectActiveEngine() {
        if (cggmpEngine != null && cggmpEngine.isCggmpEnabled() && cggmpEngine.healthCheck()) {
            return cggmpEngine;
        }
        return null;
    }

    private boolean isRealMpcEngineAvailable() {
        return selectActiveEngine() != null;
    }

    private static final class TransferContext {
        final String walletId;
        final String fromAddress;
        final String toAddress;
        final BigDecimal amount;
        final String asset;
        final String requestId;
        final Instant createdAt = Instant.now();
        String chainTxHash;
        String failureReason;

        TransferContext(String walletId, String fromAddress, String toAddress,
                        BigDecimal amount, String asset, String requestId) {
            this.walletId = walletId;
            this.fromAddress = fromAddress;
            this.toAddress = toAddress;
            this.amount = amount;
            this.asset = asset;
            this.requestId = requestId;
        }
    }

    public enum TransferStatus {
        PENDING, SIGNING, COMPLETED, EXPIRED, FAILED
    }

    public String initMultiSigTransfer(String walletId,
                                       String fromAddress,
                                       String toAddress,
                                       BigDecimal amount,
                                       String asset,
                                       String requestId,
                                       List<MpcParticipant> onlineParticipants) {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(onlineParticipants, "onlineParticipants");

        log.info("Initiating cold-wallet multi-sig transfer: walletId={}, amount={}, asset={}, requestId={}",
                walletId, amount, asset, requestId);

        MpcWallet wallet = wallets.get(walletId);
        if (wallet == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "unknown MPC wallet: " + walletId);
        }

        if (!approvalPolicy.canSign(amount, onlineParticipants)) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "MPC quorum not reached for cold-wallet transfer");
        }

        if (!approvalPolicy.isAddressWhitelisted(toAddress)) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "destination address not whitelisted: " + toAddress);
        }

        ThresholdPolicy policy = approvalPolicy.getColdWalletPolicy();
        String sessionId = UUID.randomUUID().toString();
        String txDataHex = buildTransactionHex(fromAddress, toAddress, amount, asset, requestId);

        MpcSigningSession session = new MpcSigningSession(
                sessionId, walletId, txDataHex, policy, onlineParticipants);
        sessions.put(sessionId, session);

        TransferContext ctx = new TransferContext(
                walletId, fromAddress, toAddress, amount, asset, requestId);
        transferContexts.put(sessionId, ctx);

        log.info("Cold-wallet multi-sig transfer initiated: sessionId={}, threshold={}",
                sessionId, policy.getThreshold());
        return sessionId;
    }

    public void participantSign(String sessionId) {
        MpcSigningSession session = requireSession(sessionId);
        if (isExpired(session)) {
            session.markExpired();
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.TIMEOUT,
                    "session " + sessionId + " expired before signing");
        }
        if (session.getStatus() != MpcSigningSession.SessionStatus.CREATED
                && session.getStatus() != MpcSigningSession.SessionStatus.ROUND_IN_PROGRESS) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "session " + sessionId + " is in state " + session.getStatus());
        }

        MpcWallet wallet = wallets.get(session.getWalletId());
        List<MpcKeyShare> shares = keyShares.get(session.getWalletId());
        if (shares == null) {
            shares = new ArrayList<>();
            for (MpcParticipant p : session.getParticipants()) {
                shares.add(new MpcKeyShare(
                        p.getParticipantId(),
                        "FROZEN-private-share-" + p.getParticipantId(),
                        p.getPublicKeyShareHex(),
                        "FROZEN-paillier-" + p.getParticipantId()));
            }
        }

        // GG20 退役后唯一真实引擎：CGGMP21（健康检查失败 → null → skeleton）
        CggmpMpcCryptoEngine engine = selectActiveEngine();
        if (engine != null && wallet != null && wallet.getPublicKey() != null) {
            try {
                runRealMpcSign(session, wallet, engine);
                log.info("Participant signing complete for session {} (real MPC engine: CGGMP21)",
                        sessionId);
                return;
            } catch (MpcProtocolException e) {
                session.markFailed(e.getReason(), e.getMessage(), e.getBlamedParticipant());
                TransferContext ctx = transferContexts.get(sessionId);
                if (ctx != null) {
                    ctx.failureReason = e.getMessage();
                }
                throw e;
            }
        }

        // 回退：FROZEN skeleton
        try {
            signer.runSigningRounds(session, shares);
            log.info("Participant signing complete for session {} (skeleton mode)", sessionId);
        } catch (MpcProtocolException e) {
            session.markFailed(e.getReason(), e.getMessage(), e.getBlamedParticipant());
            TransferContext ctx = transferContexts.get(sessionId);
            if (ctx != null) {
                ctx.failureReason = e.getMessage();
            }
            throw e;
        }
    }

    /**
     * 使用真实 MPC 引擎执行签名（CGGMP21 唯一路径）。
     *
     * <p>CGGMP21 路径 — {@link CggmpMpcCryptoEngine#sign} 单方调用即产
     * 完整 (r, s)，填 partialSignature 字段为 r||s 拼接（64 字节 hex）；
     * 驱动方持有全部参与方（Model A），partyIndex 不参与路由。</p>
     */
    private void runRealMpcSign(MpcSigningSession session,
                               MpcWallet wallet,
                               CggmpMpcCryptoEngine engine) {
        String sessionId = session.getSessionId();
        String publicKey = wallet.getPublicKey();
        String messageHashHex = sha256Hex(session.getTxDataHex());

        List<String> peerEndpoints = session.getParticipants().stream()
                .map(MpcParticipant::getEndpoint)
                .collect(Collectors.toList());

        log.info("Real MPC sign: session={}, participants={}, path=CGGMP21, publicKey={}...",
                sessionId, session.getParticipants().size(),
                publicKey.substring(0, Math.min(20, publicKey.length())));

        // P0-1：引擎会话 ID 必须钱包维度（keygen 与签名一致）——引擎按
        // session_id 从磁盘恢复份额；转账级随机 UUID 无对应份额会直接
        // 报 "key_share missing"。转账 sessionId 仅用于本服务记账。
        String engineSessionId = CggmpMpcCryptoEngine.walletSessionId(wallet.getWalletId());
        // 本方索引：Model A 单进程驱动全部参与方，partyIndex 不参与路由
        int partyIndex = 0;
        SignRequest req = new SignRequest(engineSessionId, publicKey,
                "cggmp-share-not-needed", messageHashHex, partyIndex, peerEndpoints);
        SignResponse resp = engine.sign(req);
        if (!resp.isSuccess()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.INVALID_SHARE,
                    "CGGMP21 sign failed: " + resp.getError());
        }
        String sig = resp.getPartialSignature();
        if (sig == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.INVALID_SHARE,
                    "CGGMP21 sign returned null signature");
        }
        // 记录 r||s 拼接（语义=完整签名）
        session.recordSignatureShare("cggmp-aggregated", sig);
        log.info("CGGMP21 sign done: session={}, engineSession={}, sig.len={}",
                sessionId, engineSessionId, sig.length());
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String aggregateAndBroadcast(String sessionId) {
        MpcSigningSession session = requireSession(sessionId);
        TransferContext ctx = transferContexts.get(sessionId);
        if (ctx == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "no transfer context for session " + sessionId);
        }
        if (isExpired(session)) {
            session.markExpired();
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.TIMEOUT,
                    "session " + sessionId + " expired before aggregation");
        }

        MpcWallet wallet = wallets.get(session.getWalletId());
        String jointPublicKeyHex = wallet != null ? wallet.getPublicKey() : "FROZEN-joint-pk";

        String signatureHex;
        CggmpMpcCryptoEngine engine = selectActiveEngine();
        try {
            if (engine != null && wallet != null && wallet.getPublicKey() != null) {
                signatureHex = runRealMpcAggregate(session, wallet, engine);
                log.info("Real MPC aggregate complete for session {} (path=CGGMP21)", sessionId);
            } else {
                signatureHex = aggregator.aggregate(session, jointPublicKeyHex);
            }
        } catch (MpcProtocolException e) {
            ctx.failureReason = e.getMessage();
            throw e;
        }

        JsonObject broadcastResult = nodeController.sendTransaction(signatureHex);
        if (broadcastResult == null || !broadcastResult.has("code")
                || broadcastResult.get("code").getAsInt() != 2000) {
            String error = broadcastResult == null ? "node rpc returned null"
                    : broadcastResult.has("message") ? broadcastResult.get("message").getAsString()
                    : "unknown node rpc error";
            session.markFailed(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "on-chain broadcast failed: " + error,
                    null);
            ctx.failureReason = error;
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "on-chain broadcast failed: " + error);
        }

        ctx.chainTxHash = broadcastResult.has("data") ? broadcastResult.get("data").getAsString() : null;
        log.info("Cold-wallet multi-sig transfer broadcast: sessionId={}, txHash={}",
                sessionId, ctx.chainTxHash);
        return ctx.chainTxHash;
    }

    private String runRealMpcAggregate(MpcSigningSession session,
                                       MpcWallet wallet,
                                       CggmpMpcCryptoEngine engine) {
        String sessionId = session.getSessionId();
        String publicKey = wallet.getPublicKey();
        String messageHashHex = sha256Hex(session.getTxDataHex());

        List<String> partialSignatures = new ArrayList<>(session.getSignatureShares().values());
        if (partialSignatures.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no partial signatures to aggregate for session " + sessionId);
        }

        log.info("Real MPC aggregate: session={}, partialSignatures={}, path=CGGMP21",
                sessionId, partialSignatures.size());

        AggregateRequest req = new AggregateRequest(sessionId, publicKey,
                messageHashHex, partialSignatures);
        AggregateResponse resp = engine.aggregate(req);
        if (!resp.isSuccess()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                    "MPC aggregate failed: " + resp.getError());
        }

        session.markCompleted(resp.getSignature());
        return resp.getSignature();
    }

    public TransferStatus getSessionStatus(String sessionId) {
        MpcSigningSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }
        if (isExpired(session) && session.getStatus() != MpcSigningSession.SessionStatus.COMPLETED) {
            session.markExpired();
        }
        return mapStatus(session);
    }

    public String getChainTxHash(String sessionId) {
        TransferContext ctx = transferContexts.get(sessionId);
        return ctx != null ? ctx.chainTxHash : null;
    }

    public String getFailureReason(String sessionId) {
        TransferContext ctx = transferContexts.get(sessionId);
        return ctx != null ? ctx.failureReason : null;
    }

    public void registerWallet(MpcWallet wallet) {
        Objects.requireNonNull(wallet, "wallet");
        wallets.put(wallet.getWalletId(), wallet);
        log.info("Registered MPC wallet: walletId={}, threshold={}",
                wallet.getWalletId(), wallet.getThreshold());
    }

    public void registerKeyShares(String walletId, List<MpcKeyShare> shares) {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(shares, "shares");
        keyShares.put(walletId, shares);
        log.info("Registered key shares for wallet {}: count={}", walletId, shares.size());
    }

    private MpcSigningSession requireSession(String sessionId) {
        MpcSigningSession session = sessions.get(sessionId);
        if (session == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "unknown session: " + sessionId);
        }
        return session;
    }

    private boolean isExpired(MpcSigningSession session) {
        TransferContext ctx = transferContexts.get(session.getSessionId());
        if (ctx == null) {
            return false;
        }
        return Duration.between(ctx.createdAt, Instant.now()).compareTo(SESSION_TIMEOUT) > 0;
    }

    private TransferStatus mapStatus(MpcSigningSession session) {
        switch (session.getStatus()) {
            case CREATED:
                return TransferStatus.PENDING;
            case ROUND_IN_PROGRESS:
            case AGGREGATING:
                return TransferStatus.SIGNING;
            case COMPLETED:
                return TransferStatus.COMPLETED;
            case EXPIRED:
                return TransferStatus.EXPIRED;
            case FAILED:
                return TransferStatus.FAILED;
            default:
                return TransferStatus.PENDING;
        }
    }

    private String buildTransactionHex(String fromAddress, String toAddress,
                                       BigDecimal amount, String asset,
                                       String requestId) {
        return "TX:" + fromAddress + ":" + toAddress + ":" + amount + ":" + asset + ":" + requestId;
    }
}
