package org.nexus.l2;

import java.math.BigDecimal;

/**
 * 挑战者 bond 质押实体。
 *
 * <p>挑战批次前需先质押 bond；挑战成功则返还 bond 并获得奖励，
 * 挑战失败则 bond 被罚没。</p>
 *
 * @since 1.2
 */
public class ChallengeBond {

    /** bond 状态 */
    public enum Status {
        /** 已质押 */
        STAKED,
        /** 已释放（挑战成功） */
        RELEASED,
        /** 已罚没（挑战失败） */
        SLASHED
    }

    /** 挑战者地址 */
    private String challengerId;

    /** 质押金额 */
    private BigDecimal amount;

    /** bond 状态 */
    private Status status;

    /** 质押时间（毫秒） */
    private long stakeTime;

    public ChallengeBond() {
    }

    public ChallengeBond(String challengerId, BigDecimal amount) {
        this.challengerId = challengerId;
        this.amount = amount;
        this.status = Status.STAKED;
        this.stakeTime = System.currentTimeMillis();
    }

    public String getChallengerId() {
        return challengerId;
    }

    public void setChallengerId(String challengerId) {
        this.challengerId = challengerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long getStakeTime() {
        return stakeTime;
    }

    public void setStakeTime(long stakeTime) {
        this.stakeTime = stakeTime;
    }
}