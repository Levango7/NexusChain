package org.nexus.wallet.signing.mpc;

import java.util.List;
import java.util.Objects;

/**
 * Immutable threshold policy for an MPC (t-of-n) wallet.
 *
 * <p>Encapsulates the threshold {@code t} and total participant count {@code n}
 * together with safety invariants:</p>
 * <ul>
 *   <li>{@code 1 &le; t &le; n}</li>
 *   <li>at least {@code t} participants must be online to sign</li>
 *   <li>the wallet is safe iff {@code t &gt; n/2} (no minority can sign alone)</li>
 * </ul>
 *
 * <p>Used by both the key-generation and signing routines to validate quorum
 * and by {@code MpcApprovalPolicy} to translate an approval count into a
 * signable / not-signable decision.</p>
 */
public final class ThresholdPolicy {

    /** Threshold t: minimum participants required to sign. */
    private final int threshold;

    /** Total number of participants n. */
    private final int totalParticipants;

    /**
     * Construct and validate a threshold policy.
     *
     * @param threshold        t (1 &le; t &le; n)
     * @param totalParticipants n (&ge; 1)
     * @throws MpcProtocolException if the parameters are invalid
     */
    public ThresholdPolicy(int threshold, int totalParticipants) {
        if (totalParticipants < 1) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.INVALID_THRESHOLD,
                    "totalParticipants must be >= 1, got " + totalParticipants);
        }
        if (threshold < 1 || threshold > totalParticipants) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.INVALID_THRESHOLD,
                    "threshold must satisfy 1 <= threshold <= totalParticipants, got t="
                            + threshold + ", n=" + totalParticipants);
        }
        this.threshold = threshold;
        this.totalParticipants = totalParticipants;
    }

    /**
     * @return the threshold t
     */
    public int getThreshold() {
        return threshold;
    }

    /**
     * @return the total participant count n
     */
    public int getTotalParticipants() {
        return totalParticipants;
    }

    /**
     * @return the maximum number of participants that may be offline while
     *         still allowing a signing round to proceed: {@code n - t}
     */
    public int getMaxOffline() {
        return totalParticipants - threshold;
    }

    /**
     * A wallet is <em>safe</em> iff no minority of participants can sign.
     *
     * @return {@code true} iff {@code t &gt; n/2}
     */
    public boolean isSafe() {
        return threshold * 2 > totalParticipants;
    }

    /**
     * Decide whether a signing round can start given the online participant list.
     *
     * @param onlineParticipants participants currently online
     * @return {@code true} iff at least {@code threshold} of the expected
     *         participants are online
     */
    public boolean isQuorumReached(List<MpcParticipant> onlineParticipants) {
        if (onlineParticipants == null) {
            return false;
        }
        long online = onlineParticipants.stream().filter(MpcParticipant::isOnline).count();
        return online >= threshold;
    }

    /**
     * Decide whether the given count of collected signature shares is sufficient
     * to aggregate the final signature.
     *
     * @param collectedShareCount number of valid shares collected so far
     * @return {@code true} iff {@code collectedShareCount >= threshold}
     */
    public boolean isSufficient(int collectedShareCount) {
        return collectedShareCount >= threshold;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThresholdPolicy)) return false;
        ThresholdPolicy that = (ThresholdPolicy) o;
        return threshold == that.threshold && totalParticipants == that.totalParticipants;
    }

    @Override
    public int hashCode() {
        return Objects.hash(threshold, totalParticipants);
    }

    @Override
    public String toString() {
        return "ThresholdPolicy{t=" + threshold + ", n=" + totalParticipants
                + ", safe=" + isSafe() + "}";
    }
}