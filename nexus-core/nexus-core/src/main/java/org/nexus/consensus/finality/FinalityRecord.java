package org.nexus.consensus.finality;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 最终化进度记录：对一个 epoch 检查点的投票权重累积与判定。
 */
public final class FinalityRecord {
    private final long epoch;
    private final byte[] checkpointHash;
    private final BigDecimal votedWeight;
    private final BigDecimal totalWeight;
    private final boolean finalized;

    public FinalityRecord(long epoch, byte[] checkpointHash,
                          BigDecimal votedWeight, BigDecimal totalWeight, boolean finalized) {
        this.epoch = epoch;
        this.checkpointHash = checkpointHash;
        this.votedWeight = votedWeight;
        this.totalWeight = totalWeight;
        this.finalized = finalized;
    }

    public long getEpoch() { return epoch; }
    public byte[] getCheckpointHash() { return checkpointHash; }
    public BigDecimal getVotedWeight() { return votedWeight; }
    public BigDecimal getTotalWeight() { return totalWeight; }
    public boolean isFinalized() { return finalized; }

    /**
     * 确认度百分比（0-100）：已投票权重 / 总权重。
     */
    public int progressPercent() {
        if (totalWeight == null || totalWeight.signum() == 0) return 0;
        return votedWeight.multiply(BigDecimal.valueOf(100))
                .divide(totalWeight, 0, RoundingMode.HALF_UP).intValue();
    }
}
