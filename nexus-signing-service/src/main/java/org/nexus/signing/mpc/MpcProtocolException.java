package org.nexus.signing.mpc;

/**
 * Exception raised when an MPC (GG18/GG20) protocol round fails.
 *
 * <p>Carries a typed {@link Reason} so callers can distinguish transient
 * network timeouts from cryptographic failures such as malformed shares or
 * detected malicious participants. The exception is unchecked so it can be
 * propagated through Spring service boundaries without forcing every caller
 * to declare a throws clause.</p>
 */
public class MpcProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Structured reason for the protocol failure. */
    private final Reason reason;

    /** Identifier of the participant blamed for the failure, or {@code null}. */
    private final String blamedParticipant;

    /**
     * Categorised failure reasons for MPC protocol rounds.
     */
    public enum Reason {
        /** A protocol round exceeded its time budget. */
        TIMEOUT,
        /** A participant supplied a malformed or invalid share. */
        INVALID_SHARE,
        /** A participant was identified as malicious (aborted, sent bad proofs). */
        MALICIOUS_PARTICIPANT,
        /** Fewer than {@code threshold} participants are reachable. */
        QUORUM_NOT_REACHED,
        /** Share verification (Paillier / ZK proof) failed. */
        SHARE_VERIFICATION_FAILED,
        /** The signing session is in an unexpected state for the requested op. */
        ILLEGAL_STATE,
        /** The configured threshold parameters are invalid. */
        INVALID_THRESHOLD
    }

    /**
     * Build an exception with the given reason and message.
     *
     * @param reason  failure category
     * @param message human-readable detail
     */
    public MpcProtocolException(Reason reason, String message) {
        super(message);
        this.reason = reason;
        this.blamedParticipant = null;
    }

    /**
     * Build an exception with a blamed participant.
     *
     * @param reason             failure category
     * @param message            human-readable detail
     * @param blamedParticipant  ID of the participant blamed for the failure
     */
    public MpcProtocolException(Reason reason, String message, String blamedParticipant) {
        super(message);
        this.reason = reason;
        this.blamedParticipant = blamedParticipant;
    }

    /**
     * Build an exception wrapping an underlying cause.
     *
     * @param reason  failure category
     * @param message human-readable detail
     * @param cause   underlying cause
     */
    public MpcProtocolException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.blamedParticipant = null;
    }

    /**
     * @return the structured failure reason
     */
    public Reason getReason() {
        return reason;
    }

    /**
     * @return the blamed participant ID, or {@code null} if no single party is blamed
     */
    public String getBlamedParticipant() {
        return blamedParticipant;
    }
}