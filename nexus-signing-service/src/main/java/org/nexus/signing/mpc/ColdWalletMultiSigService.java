package org.nexus.signing.mpc;

import com.google.gson.JsonObject;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.MpcCryptoEngine;
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
 * <p>The service wires together the GG18/GG20 building blocks:</p>
 * <ol>
 *   <li>{@link #initMultiSigTransfer} — construct the transaction and start an
 *       {@link MpcSigningSession}, validating the MPC quorum via
 *       {@link MpcApprovalPolicy#canSign}.</li>
 *   <li>{@link #participantSign} — each participant executes its local MPC
 *       rounds through {@link MpcSigner#runSigningRounds}.</li>
 *   <li>{@link #aggregateAndBroadcast} — {@link MpcSignatureAggregator} combines
 *       the shares into the final signature and the signed transaction is
 *       submitted on-chain directly via {@link NodeController}（解耦：不再绕行
 *       gateway 的 OnChainExecutionClient，签名服务直接广播到链节点）。</li>
 *   <li>{@link #getSessionStatus} — query the session lifecycle
 *       (PENDING / SIGNING / COMPLETED / EXPIRED / FAILED).</li>
 * </ol>
 *
 * <p>Sessions are held in an in-memory concurrent map; production wiring should
 * persist them to a durable store. A session expires after
 * {@link #SESSION_TIMEOUT} without reaching COMPLETED.</p>
 */
@Service
public class ColdWalletMultiSigService {

    private static final Logger log = LoggerFactory.getLogger(ColdWalletMultiSigService.class);

    /** Default session timeout: 5 minutes. */
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(5);

    /** In-memory session store: sessionId -> session. */
    private final Map<String, MpcSigningSession> sessions = new ConcurrentHashMap<>();

    /** In-memory transfer context store: sessionId -> transfer context. */
    private final Map<String, TransferContext> transferContexts = new ConcurrentHashMap<>();

    /** In-memory MPC wallet store: walletId -> wallet. */
    private final Map<String, MpcWallet> wallets = new ConcurrentHashMap<>();

    /** In-memory key share store: walletId -> per-participant shares. */
    private final Map<String, List<MpcKeyShare>> keyShares = new ConcurrentHashMap<>();

    private final MpcSigner signer;
    private final MpcSignatureAggregator aggregator;
    private final MpcApprovalPolicy approvalPolicy;
    private final NodeController nodeController;

    /**
     * MPC 密码学引擎（P5-T3 真实化）。可选注入：
     * <ul>
     *   <li>引擎可用且 {@link MpcCryptoEngine#healthCheck()} 返回 true 时，
     *       {@link #participantSign} 和 {@link #aggregateAndBroadcast} 使用真实
     *       DKG/Sign/Aggregate 调用，替代 FROZEN skeleton。</li>
     *   <li>引擎不可用（null 或 healthCheck false）时，回退到
     *       {@link MpcSigner}/{@link MpcSignatureAggregator} skeleton。</li>
     * </ul>
     */
    @Autowired(required = false)
    private MpcCryptoEngine mpcCryptoEngine;

    /**
     * Construct the service with its collaborators.
     *
     * <p>解耦改造（设计文档 §5.2）：原依赖 {@code OnChainExecutionClient}（经 gateway
     * 广播），现改为直接注入 {@link NodeController}（链节点 RPC 封装），签名服务直接
     * 广播签名结果到链上，减少一跳网络延迟。</p>
     *
     * @param signer          MPC signer
     * @param aggregator      signature aggregator
     * @param approvalPolicy  MPC-aware approval policy
     * @param nodeController  链节点 RPC 客户端，直接广播签名结果
     */
    @Autowired
    public ColdWalletMultiSigService(MpcSigner signer,
                                     MpcSignatureAggregator aggregator,
                                     @Qualifier("mpcApprovalPolicy") MpcApprovalPolicy approvalPolicy,
                                     NodeController nodeController) {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.nodeController = Objects.requireNonNull(nodeController, "nodeController");
        log.info("ColdWalletMultiSigService initialised (decoupled from OnChainExecutionClient, broadcasting via NodeController)");
    }

    /**
     * 判断真实 MPC 引擎是否可用。
     *
     * @return {@code true} 若引擎已注入且 healthCheck 通过
     */
    private boolean isRealMpcEngineAvailable() {
        return mpcCryptoEngine != null && mpcCryptoEngine.healthCheck();
    }

    /**
     * Context tracked for each in-flight transfer.
     */
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

    /**
     * Lifecycle status exposed to callers.
     */
    public enum TransferStatus {
        /** Session created; waiting for participants to start signing. */
        PENDING,
        /** MPC signing rounds in progress. */
        SIGNING,
        /** Signature aggregated and broadcast on-chain. */
        COMPLETED,
        /** Session expired before completion. */
        EXPIRED,
        /** Session failed (quorum not reached, invalid shares, etc.). */
        FAILED
    }

    // ------------------------------------------------------------------
    // 1. Initiate multi-sig transfer
    // ------------------------------------------------------------------

    /**
     * Initiate a cold-wallet multi-sig transfer.
     *
     * <p>Validates the MPC quorum via the approval policy, constructs the
     * transaction data, and starts a new {@link MpcSigningSession}.</p>
     *
     * @param walletId          MPC wallet ID
     * @param fromAddress       source cold-wallet address
     * @param toAddress         destination address
     * @param amount            transfer amount
     * @param asset             asset symbol (e.g. NEX, USDT)
     * @param requestId         external request ID for idempotency
     * @param onlineParticipants currently online MPC participants
     * @return the new session ID
     * @throws MpcProtocolException if the quorum is not reached or the wallet is unknown
     */
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

    // ------------------------------------------------------------------
    // 2. Participant signing
    // ------------------------------------------------------------------

    /**
     * Execute the local MPC signing rounds for the given session.
     *
     * <p>In a real deployment each participant calls this on its own node;
     * here the skeleton drives all participants' rounds in-process.</p>
     *
     * @param sessionId session ID
     * @throws MpcProtocolException if the session is unknown, expired, or a round fails
     */
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
            // FROZEN per ADR-001: load key shares from the durable per-node share store.
            // 解冻条件见 docs/adr/ADR-001-research-layer-freeze.md
            shares = new ArrayList<>();
            for (MpcParticipant p : session.getParticipants()) {
                shares.add(new MpcKeyShare(
                        p.getParticipantId(),
                        "FROZEN-private-share-" + p.getParticipantId(),
                        p.getPublicKeyShareHex(),
                        "FROZEN-paillier-" + p.getParticipantId()));
            }
        }

        // P5-T3：如果真实 MPC 引擎可用，使用真实 DKG/Sign/Aggregate 替代 FROZEN skeleton
        if (isRealMpcEngineAvailable() && wallet != null && wallet.getPublicKey() != null) {
            try {
                runRealMpcSign(session, wallet, shares);
                log.info("Participant signing complete for session {} (real MPC engine)", sessionId);
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

        // 回退：FROZEN skeleton 模式
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
     * 使用真实 MPC 引擎执行签名（P5-T3）。
     *
     * <p>为每个在线参与方调用 {@link MpcCryptoEngine#sign}，产出部分签名，
     * 收集到 session 中。替代 FROZEN skeleton 的 {@link MpcSigner#runSigningRounds}。</p>
     *
     * @param session 签名会话
     * @param wallet  MPC 钱包（提供聚合公钥）
     * @param shares  各参与方密钥份额
     */
    private void runRealMpcSign(MpcSigningSession session,
                                MpcWallet wallet,
                                List<MpcKeyShare> shares) {
        String sessionId = session.getSessionId();
        String publicKey = wallet.getPublicKey();
        String messageHashHex = sha256Hex(session.getTxDataHex());

        // 提取对端端点
        List<String> peerEndpoints = session.getParticipants().stream()
                .map(MpcParticipant::getEndpoint)
                .collect(Collectors.toList());

        log.info("Real MPC sign: session={}, participants={}, publicKey={}...",
                sessionId, session.getParticipants().size(),
                publicKey.substring(0, Math.min(20, publicKey.length())));

        // 为每个参与方调用 engine.sign
        for (int i = 0; i < session.getParticipants().size(); i++) {
            MpcParticipant p = session.getParticipants().get(i);
            MpcKeyShare share = findShare(shares, p.getParticipantId());
            if (share == null) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_STATE,
                        "no key share for participant " + p.getParticipantId());
            }

            SignRequest req = new SignRequest(sessionId, publicKey,
                    share.getPrivateShareHex(), messageHashHex, i, peerEndpoints);
            SignResponse resp = mpcCryptoEngine.sign(req);
            if (!resp.isSuccess()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "MPC sign failed for participant " + p.getParticipantId()
                                + ": " + resp.getError());
            }
            session.recordSignatureShare(p.getParticipantId(), resp.getPartialSignature());
            log.debug("Real MPC sign party {} done: {}", i, p.getParticipantId());
        }
        session.markAggregating();
    }

    /**
     * 在 shares 列表中查找指定参与方的密钥份额。
     *
     * @param shares       密钥份额列表
     * @param participantId 参与方 ID
     * @return 密钥份额，或 {@code null} 若未找到
     */
    private static MpcKeyShare findShare(List<MpcKeyShare> shares, String participantId) {
        for (MpcKeyShare s : shares) {
            if (s.getParticipantId().equals(participantId)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 计算字符串的 SHA-256 哈希（hex 编码，32 字节）。
     *
     * @param input 输入字符串
     * @return SHA-256 哈希（hex，64 字符）
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ------------------------------------------------------------------
    // 3. Aggregate and broadcast
    // ------------------------------------------------------------------

    /**
     * Aggregate the collected signature shares and broadcast the signed
     * transaction on-chain.
     *
     * @param sessionId session ID
     * @return the on-chain transaction hash
     * @throws MpcProtocolException if aggregation fails or the session is invalid
     */
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
        try {
            // P5-T3：如果真实 MPC 引擎可用，使用真实 Aggregate 替代 FROZEN skeleton
            if (isRealMpcEngineAvailable() && wallet != null && wallet.getPublicKey() != null) {
                signatureHex = runRealMpcAggregate(session, wallet);
                log.info("Real MPC aggregate complete for session {}", sessionId);
            } else {
                signatureHex = aggregator.aggregate(session, jointPublicKeyHex);
            }
        } catch (MpcProtocolException e) {
            ctx.failureReason = e.getMessage();
            throw e;
        }

        // FROZEN per ADR-001: encode the signature into the blockchain-specific transaction
        //       format (e.g. RLP for EVM, raw tx for Bitcoin) before submitting.
        //       解冻条件见 docs/adr/ADR-001-research-layer-freeze.md
        // 解耦改造：直接通过 NodeController 广播签名结果到链节点，不再构造 WalletTransactionRequest
        // 经 OnChainExecutionClient → gateway → 链节点。减少一跳网络延迟，符合「签名服务负责签名+广播」边界。
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

    /**
     * 使用真实 MPC 引擎执行签名聚合（P5-T3）。
     *
     * <p>收集 session 中的部分签名，调用 {@link MpcCryptoEngine#aggregate}，
     * 产出最终 ECDSA 签名。替代 FROZEN skeleton 的 {@link MpcSignatureAggregator#aggregate}。</p>
     *
     * @param session 签名会话
     * @param wallet  MPC 钱包（提供聚合公钥）
     * @return 最终签名（hex）
     */
    private String runRealMpcAggregate(MpcSigningSession session, MpcWallet wallet) {
        String sessionId = session.getSessionId();
        String publicKey = wallet.getPublicKey();
        String messageHashHex = sha256Hex(session.getTxDataHex());

        // 收集部分签名
        List<String> partialSignatures = new ArrayList<>(session.getSignatureShares().values());
        if (partialSignatures.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no partial signatures to aggregate for session " + sessionId);
        }

        log.info("Real MPC aggregate: session={}, partialSignatures={}",
                sessionId, partialSignatures.size());

        AggregateRequest req = new AggregateRequest(sessionId, publicKey,
                messageHashHex, partialSignatures);
        AggregateResponse resp = mpcCryptoEngine.aggregate(req);
        if (!resp.isSuccess()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                    "MPC aggregate failed: " + resp.getError());
        }

        session.markCompleted(resp.getSignature());
        return resp.getSignature();
    }

    // ------------------------------------------------------------------
    // 4. Query session status
    // ------------------------------------------------------------------

    /**
     * Query the status of a multi-sig transfer session.
     *
     * @param sessionId session ID
     * @return the transfer status, or {@code null} if the session is unknown
     */
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

    /**
     * @param sessionId session ID
     * @return the on-chain tx hash for a completed session, or {@code null}
     */
    public String getChainTxHash(String sessionId) {
        TransferContext ctx = transferContexts.get(sessionId);
        return ctx != null ? ctx.chainTxHash : null;
    }

    /**
     * @param sessionId session ID
     * @return the failure reason for a failed session, or {@code null}
     */
    public String getFailureReason(String sessionId) {
        TransferContext ctx = transferContexts.get(sessionId);
        return ctx != null ? ctx.failureReason : null;
    }

    // ------------------------------------------------------------------
    // Wallet / share registration helpers
    // ------------------------------------------------------------------

    /**
     * Register an MPC wallet with this service.
     *
     * @param wallet the MPC wallet
     */
    public void registerWallet(MpcWallet wallet) {
        Objects.requireNonNull(wallet, "wallet");
        wallets.put(wallet.getWalletId(), wallet);
        log.info("Registered MPC wallet: walletId={}, threshold={}",
                wallet.getWalletId(), wallet.getThreshold());
    }

    /**
     * Register the per-participant key shares for a wallet.
     *
     * @param walletId wallet ID
     * @param shares   per-participant key shares
     */
    public void registerKeyShares(String walletId, List<MpcKeyShare> shares) {
        Objects.requireNonNull(walletId, "walletId");
        Objects.requireNonNull(shares, "shares");
        keyShares.put(walletId, shares);
        log.info("Registered key shares for wallet {}: count={}", walletId, shares.size());
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * @param sessionId session ID
     * @return the session
     * @throws MpcProtocolException if the session is unknown
     */
    private MpcSigningSession requireSession(String sessionId) {
        MpcSigningSession session = sessions.get(sessionId);
        if (session == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "unknown session: " + sessionId);
        }
        return session;
    }

    /**
     * @param session session
     * @return {@code true} iff the session has exceeded its timeout
     */
    private boolean isExpired(MpcSigningSession session) {
        TransferContext ctx = transferContexts.get(session.getSessionId());
        if (ctx == null) {
            return false;
        }
        return Duration.between(ctx.createdAt, Instant.now()).compareTo(SESSION_TIMEOUT) > 0;
    }

    /**
     * Map the internal session status to the public transfer status.
     *
     * @param session session
     * @return transfer status
     */
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

    /**
     * Build the hex-encoded transaction data to be signed.
     *
     * @param fromAddress source
     * @param toAddress   destination
     * @param amount      amount
     * @param asset       asset symbol
     * @param requestId   request ID
     * @return hex-encoded transaction data
     */
    private String buildTransactionHex(String fromAddress, String toAddress,
                                       BigDecimal amount, String asset,
                                       String requestId) {
        // FROZEN per ADR-001: encode the actual blockchain transaction (RLP / protobuf / etc.)
        //       For now we produce a deterministic placeholder.
        //       解冻条件见 docs/adr/ADR-001-research-layer-freeze.md
        return "TX:" + fromAddress + ":" + toAddress + ":" + amount + ":" + asset + ":" + requestId;
    }
}