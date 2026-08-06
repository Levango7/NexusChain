package org.nexus.wallet.signing.mpc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detailed state of a single GG18/GG20 MPC signing session.
 *
 * <p>This is the rich counterpart to the lightweight {@link MpcSignSession}
 * entity: it tracks the per-round messages exchanged between participants,
 * the current round number, and the per-participant signature shares as they
 * arrive. The lifecycle is:</p>
 * <pre>
 *   CREATED -> ROUND_1 -> ROUND_2 -> ... -> ROUND_K -> AGGREGATING -> COMPLETED
 *                                                          |-> FAILED
 *                                                          |-> EXPIRED
 * </pre>
 *
 * <p>Instances are <b>not</b> thread-safe; callers must synchronise externally
 * (e.g. via the owning service) when mutating session state.</p>
 */
public class MpcSigningSession {

    /** Total number of GG18/GG20 signing rounds (excluding aggregation). */
    public static final int SIGN_ROUNDS = 7;

    /** Unique session ID. */
    private final String sessionId;

    /** Wallet ID this session signs for. */
    private final String walletId;

    /** Transaction data to be signed (raw bytes encoded as hex). */
    private final String txDataHex;

    /** Threshold policy governing this session. */
    private final ThresholdPolicy thresholdPolicy;

    /** Participants taking part in this signing session. */
    private final List<MpcParticipant> participants;

    /** Current session status. */
    private SessionStatus status = SessionStatus.CREATED;

    /** Current round number (1-based; 0 means not yet started). */
    private int currentRound = 0;

    /** Per-round received messages: round -> (senderId -> messageHex). */
    private final Map<Integer, Map<String, String>> roundMessages = new HashMap<>();

    /** Per-participant signature shares collected in the final round. */
    private final Map<String, String> signatureShares = new HashMap<>();

    /** Final combined signature (hex), populated when status = COMPLETED. */
    private String combinedSignatureHex;

    /** Participant blamed for failure, or {@code null}. */
    private String blamedParticipant;

    /** Timestamps. */
    private final LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime completedAt;

    /**
     * Session status.
     */
    public enum SessionStatus {
        /** Session created but round 1 not yet started. */
        CREATED,
        /** Round 1..K in progress. */
        ROUND_IN_PROGRESS,
        /** All rounds done; shares being aggregated. */
        AGGREGATING,
        /** Final signature produced. */
        COMPLETED,
        /** A round failed (invalid share / malicious party). */
        FAILED,
        /** The session exceeded its time budget. */
        EXPIRED
    }

    /**
     * Construct a new signing session.
     *
     * @param sessionId       unique session ID
     * @param walletId        wallet ID
     * @param txDataHex       transaction data (hex)
     * @param thresholdPolicy threshold policy
     * @param participants    participants (must satisfy the policy quorum)
     */
    public MpcSigningSession(String sessionId,
                             String walletId,
                             String txDataHex,
                             ThresholdPolicy thresholdPolicy,
                             List<MpcParticipant> participants) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.walletId = Objects.requireNonNull(walletId, "walletId");
        this.txDataHex = Objects.requireNonNull(txDataHex, "txDataHex");
        this.thresholdPolicy = Objects.requireNonNull(thresholdPolicy, "thresholdPolicy");
        this.participants = new ArrayList<>(Objects.requireNonNull(participants, "participants"));
    }

    /**
     * Advance to the given round number, validating monotonic progression.
     *
     * @param round target round (1-based)
     */
    public void advanceToRound(int round) {
        if (round <= currentRound) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "cannot move backwards from round " + currentRound + " to " + round);
        }
        if (round > SIGN_ROUNDS) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "round " + round + " exceeds SIGN_ROUNDS=" + SIGN_ROUNDS);
        }
        this.currentRound = round;
        this.status = SessionStatus.ROUND_IN_PROGRESS;
    }

    /**
     * Record a message received from a participant in the current round.
     *
     * @param round       round number
     * @param senderId    sending participant ID
     * @param messageHex  message body (hex)
     */
    public void recordMessage(int round, String senderId, String messageHex) {
        roundMessages
                .computeIfAbsent(round, k -> new HashMap<>())
                .put(senderId, messageHex);
    }

    /**
     * Record a signature share from a participant.
     *
     * @param participantId participant ID
     * @param shareHex      signature share (hex)
     */
    public void recordSignatureShare(String participantId, String shareHex) {
        signatureShares.put(participantId, shareHex);
    }

    /**
     * Mark the session as aggregating shares.
     */
    public void markAggregating() {
        this.status = SessionStatus.AGGREGATING;
    }

    /**
     * Mark the session completed with the given combined signature.
     *
     * @param combinedSignatureHex final signature (hex)
     */
    public void markCompleted(String combinedSignatureHex) {
        this.combinedSignatureHex = combinedSignatureHex;
        this.status = SessionStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark the session failed, optionally blaming a participant.
     *
     * @param reason             failure reason
     * @param message            detail message
     * @param blamedParticipant  blamed participant ID or {@code null}
     */
    public void markFailed(MpcProtocolException.Reason reason,
                           String message,
                           String blamedParticipant) {
        this.status = SessionStatus.FAILED;
        this.blamedParticipant = blamedParticipant;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark the session expired.
     */
    public void markExpired() {
        this.status = SessionStatus.EXPIRED;
        this.completedAt = LocalDateTime.now();
    }

    // --- Getters ---

    public String getSessionId() { return sessionId; }
    public String getWalletId() { return walletId; }
    public String getTxDataHex() { return txDataHex; }
    public ThresholdPolicy getThresholdPolicy() { return thresholdPolicy; }
    public List<MpcParticipant> getParticipants() { return participants; }
    public SessionStatus getStatus() { return status; }
    public int getCurrentRound() { return currentRound; }
    public Map<Integer, Map<String, String>> getRoundMessages() { return roundMessages; }
    public Map<String, String> getSignatureShares() { return signatureShares; }
    public String getCombinedSignatureHex() { return combinedSignatureHex; }
    public String getBlamedParticipant() { return blamedParticipant; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }

    /**
     * @return the number of distinct signature shares collected so far
     */
    public int getCollectedShareCount() {
        return signatureShares.size();
    }

    /**
     * @return {@code true} iff enough shares have been collected to aggregate
     */
    public boolean hasSufficientShares() {
        return thresholdPolicy.isSufficient(getCollectedShareCount());
    }

    @Override
    public String toString() {
        return "MpcSigningSession{sessionId='" + sessionId + "', walletId='" + walletId
                + "', status=" + status + ", round=" + currentRound
                + ", shares=" + getCollectedShareCount() + "/" + thresholdPolicy.getThreshold() + "}";
    }
}