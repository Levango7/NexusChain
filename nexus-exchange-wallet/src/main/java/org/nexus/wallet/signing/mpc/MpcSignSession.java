package org.nexus.wallet.signing.mpc;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * MPC signing session tracking the state of a distributed signing round.
 *
 * <p>Each participant contributes a signature share; once {@code threshold}
 * shares are collected, the shares are combined into the final signature.
 * The session tracks which participants have already contributed and the
 * partial shares received.</p>
 */
public class MpcSignSession {

    /** Unique session ID. */
    private String sessionId;

    /** Wallet ID this session signs for. */
    private String walletId;

    /** Transaction data to be signed (raw bytes encoded as hex). */
    private String txData;

    /** Set of participant IDs that have already contributed a share. */
    private Set<String> signedParticipants = new HashSet<>();

    /** Map of participant ID -> signature share (encoded as hex). */
    private Map<String, String> signatureShares = new HashMap<>();

    /** Current session status. */
    private SessionStatus status = SessionStatus.PENDING;

    /** Timestamp when the session was created. */
    private LocalDateTime createdAt;

    /** Timestamp when the session was completed (signature combined). */
    private LocalDateTime completedAt;

    /** Final combined signature, populated when status = COMPLETED. */
    String combinedSignature;

    public enum SessionStatus {
        PENDING, COLLECTING, COMPLETED, FAILED, EXPIRED
    }

    // --- Getters and Setters ---

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public String getTxData() { return txData; }
    public void setTxData(String txData) { this.txData = txData; }

    public Set<String> getSignedParticipants() { return signedParticipants; }
    public void setSignedParticipants(Set<String> signedParticipants) { this.signedParticipants = signedParticipants; }

    public Map<String, String> getSignatureShares() { return signatureShares; }
    public void setSignatureShares(Map<String, String> signatureShares) { this.signatureShares = signatureShares; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getCombinedSignature() { return combinedSignature; }
    public void setCombinedSignature(String combinedSignature) { this.combinedSignature = combinedSignature; }
}