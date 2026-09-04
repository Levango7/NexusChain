package org.nexus.signing.mpc;

import com.google.gson.JsonObject;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.mpc.cggmp.CggmpMpcCryptoEngine;
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
 * <p>v2.2.0 H 批：编排层支持 CGGMP21 路径切换。
 * 通过 {@code mpc.engine.cggmp-enabled} 配置选择：</p>
 * <ul>
 *   <li>{@code true} — 走 {@link CggmpMpcCryptoEngine}（CGGMP21 路径）</li>
 *   <li>{@code false}（默认）— 走 {@link MpcCryptoEngine}（GG20 路径）</li>
 * </ul>
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

    /** GG20 路径引擎（P5-T3）。H 批：路径选择为 CGGMP 关闭时使用。 */
    @Autowired(required = false)
    private MpcCryptoEngine mpcCryptoEngine;

    /** CGGMP21 路径引擎（H 批新增）。 */
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
        log.info("ColdWalletMultiSigService initialised (H batch: cggmp engine path selectable)");
    }

    /**
     * 选择当前真实 MPC 引擎（GG20 或 CGGMP21）。
     *
     * <p>H 批路径选择：</p>
     * <ol>
     *   <li>CGGMP21 路径（{@link CggmpMpcCryptoEngine#isCggmpEnabled()} true
     *       且注入可用且 healthCheck 通过）→ 返回 cggmpEngine</li>
     *   <li>GG20 路径（{@link MpcCryptoEngine} 注入可用且 healthCheck 通过）
     *       → 返回 mpcCryptoEngine</li>
     *   <li>都不可用 → 返回 null（走 FROZEN skeleton）</li>
     * </ol>
     */
    private MpcCryptoEngine selectActiveEngine() {
        if (cggmpEngine != null && cggmpEngine.isCggmpEnabled() && cggmpEngine.healthCheck()) {
            return cggmpEngine;
        }
        if (mpcCryptoEngine != null && mpcCryptoEngine.healthCheck()) {
            return mpcCryptoEngine;
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

        // P5-T3 + H 批：选择当前真实引擎（GG20 or CGGMP21）
        MpcCryptoEngine engine = selectActiveEngine();
        if (engine != null && wallet != null && wallet.getPublicKey() != null) {
            try {
                runRealMpcSign(session, wallet, shares, engine);
                log.info("Participant signing complete for session {} (real MPC engine: {})",
                        sessionId,
                        engine instanceof CggmpMpcCryptoEngine ? "CGGMP21" : "GG20");
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
     * 使用真实 MPC 引擎执行签名（P5-T3 + H 批路径选择）。
     *
     * <p>路径选择：</p>
     * <ul>
     *   <li>CGGMP21 路径 — {@link CggmpMpcCryptoEngine#sign} 单方调用即产
     *       完整 (r, s)，填 partialSignature 字段为 r||s 拼接（64 字节 hex）</li>
     *   <li>GG20 路径 — 各方调一次 engine.sign，产 partialSig 收集
     *       （与原 P5-T3 行为一致）</li>
     * </ul>
     */
    private void runRealMpcSign(MpcSigningSession session,
                                MpcWallet wallet,
                                List<MpcKeyShare> shares,
                                MpcCryptoEngine engine) {
        String sessionId = session.getSessionId();
        String publicKey = wallet.getPublicKey();
        String messageHashHex = sha256Hex(session.getTxDataHex());

        List<String> peerEndpoints = session.getParticipants().stream()
                .map(MpcParticipant::getEndpoint)
                .collect(Collectors.toList());

        log.info("Real MPC sign: session={}, participants={}, path={}, publicKey={}...",
                sessionId, session.getParticipants().size(),
                engine instanceof CggmpMpcCryptoEngine ? "CGGMP21" : "GG20",
                publicKey.substring(0, Math.min(20, publicKey.length())));

        // CGGMP21 路径：单方调用即可（r/s 在 mpc-engine 进程内已产出）
        if (engine instanceof CggmpMpcCryptoEngine) {
            // 本方索引：取第一位参与方（H 批：单进程 = 单方，partyIndex=0）
            int partyIndex = 0;
            SignRequest req = new SignRequest(sessionId, publicKey,
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
            // 记录 r||s 拼接（语义=完整签名）—— 与 GG20 aggregate 行为对齐
            session.recordSignatureShare("cggmp-aggregated", sig);
            log.info("CGGMP21 sign done: session={}, sig.len={}", sessionId, sig.length());
            return;
        }

        // GG20 路径：每方各调一次 sign
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
            SignResponse resp = engine.sign(req);
            if (!resp.isSuccess()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "MPC sign failed for participant " + p.getParticipantId()
                                + ": " + resp.getError());
            }
            session.recordSignatureShare(p.getParticipantId(), resp.getPartialSignature());
            log.debug("GG20 sign party {} done: {}", i, p.getParticipantId());
        }
        session.markAggregating();
    }

    private static MpcKeyShare findShare(List<MpcKeyShare> shares, String participantId) {
        for (MpcKeyShare s : shares) {
            if (s.getParticipantId().equals(participantId)) {
                return s;
            }
        }
        return null;
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
        MpcCryptoEngine engine = selectActiveEngine();
        try {
            if (engine != null && wallet != null && wallet.getPublicKey() != null) {
                signatureHex = runRealMpcAggregate(session, wallet, engine);
                log.info("Real MPC aggregate complete for session {} (path={})",
                        sessionId,
                        engine instanceof CggmpMpcCryptoEngine ? "CGGMP21" : "GG20");
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
                                       MpcCryptoEngine engine) {
        String sessionId = session.getSessionId();
        String publicKey = wallet.getPublicKey();
        String messageHashHex = sha256Hex(session.getTxDataHex());

        List<String> partialSignatures = new ArrayList<>(session.getSignatureShares().values());
        if (partialSignatures.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no partial signatures to aggregate for session " + sessionId);
        }

        log.info("Real MPC aggregate: session={}, partialSignatures={}, path={}",
                sessionId, partialSignatures.size(),
                engine instanceof CggmpMpcCryptoEngine ? "CGGMP21" : "GG20");

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
