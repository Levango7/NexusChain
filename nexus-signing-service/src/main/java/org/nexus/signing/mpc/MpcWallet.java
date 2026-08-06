package org.nexus.signing.mpc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MPC (Multi-Party Computation) wallet entity.
 *
 * <p>An MPC wallet is shared among {@code participants} with a {@code threshold}
 * such that any {@code threshold} participants can collaboratively sign a
 * transaction, but no subset smaller than {@code threshold} can. The joint
 * public key is the only on-chain identity; private key shares never exist
 * in a single location.</p>
 */
public class MpcWallet {

    /** Unique wallet ID. */
    private String walletId;

    /** List of participant identifiers (e.g. node IDs or operator IDs). */
    private List<String> participants = new ArrayList<>();

    /** Threshold number of participants required to sign. */
    private Integer threshold;

    /** Joint public key (the only on-chain identity). */
    private String publicKey;

    /** Current wallet status. */
    private WalletStatus status = WalletStatus.ACTIVE;

    /** Timestamp when the wallet was created. */
    private LocalDateTime createdAt;

    /** Timestamp of the most recent key rotation. */
    private LocalDateTime lastRotatedAt;

    /** Optional human-readable label. */
    private String label;

    public enum WalletStatus {
        ACTIVE, ROTATING, FROZEN, DECOMMISSIONED
    }

    // --- Getters and Setters ---

    public String getWalletId() { return walletId; }
    public void setWalletId(String walletId) { this.walletId = walletId; }

    public List<String> getParticipants() { return participants; }
    public void setParticipants(List<String> participants) { this.participants = participants; }

    public Integer getThreshold() { return threshold; }
    public void setThreshold(Integer threshold) { this.threshold = threshold; }

    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }

    public WalletStatus getStatus() { return status; }
    public void setStatus(WalletStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastRotatedAt() { return lastRotatedAt; }
    public void setLastRotatedAt(LocalDateTime lastRotatedAt) { this.lastRotatedAt = lastRotatedAt; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}