package org.nexus.consensus.finality;

import java.util.Arrays;

/**
 * 双签证据（equivocation）：同一验证者在同一 epoch 对两个不同检查点的投票。
 */
public final class EquivocationEvidence {

    private final Vote voteA;
    private final Vote voteB;

    public EquivocationEvidence(Vote a, Vote b) {
        if (a.getEpoch() != b.getEpoch()) {
            throw new IllegalArgumentException("equivocation requires same epoch");
        }
        if (!a.getValidatorAddress().equals(b.getValidatorAddress())) {
            throw new IllegalArgumentException("equivocation requires same validator");
        }
        if (Arrays.equals(a.getCheckpointHash(), b.getCheckpointHash())) {
            throw new IllegalArgumentException("equivocation requires different checkpoints");
        }
        this.voteA = a;
        this.voteB = b;
    }

    public Vote getVoteA() { return voteA; }
    public Vote getVoteB() { return voteB; }
    public String getOffender() { return voteA.getValidatorAddress(); }
}
