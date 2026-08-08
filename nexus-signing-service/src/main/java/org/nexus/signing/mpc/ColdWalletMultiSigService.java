package org.nexus.signing.mpc;

import com.google.gson.JsonObject;
import org.nexus.signing.controller.NodeController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

        try {
            signer.runSigningRounds(session, shares);
            log.info("Participant signing complete for session {}", sessionId);
        } catch (MpcProtocolException e) {
            session.markFailed(e.getReason(), e.getMessage(), e.getBlamedParticipant());
            TransferContext ctx = transferContexts.get(sessionId);
            if (ctx != null) {
                ctx.failureReason = e.getMessage();
            }
            throw e;
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
            signatureHex = aggregator.aggregate(session, jointPublicKeyHex);
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