package org.nexus.governance.delegation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 锁仓记录实体。
 *
 * <p>描述一次为获取更高投票权重而进行的锁仓：锁仓金额、起止时间。
 * 锁仓越久，{@link VotingPowerCalculator} 给予的权重倍数越高；
 * 随时间推进，未解锁锁仓的权重按 sqrt 衰减，已解锁锁仓的加权失效。</p>
 *
 * @since 1.4
 */
public class LockRecord {

    /** 锁仓人地址 */
    private final String voter;

    /** 锁仓金额 */
    private final BigDecimal amount;

    /** 锁仓起始时间 */
    private final Instant lockStart;

    /** 锁仓到期时间 */
    private final Instant lockEnd;

    public LockRecord(String voter, BigDecimal amount, Instant lockStart, Instant lockEnd) {
        if (voter == null || amount == null || lockStart == null || lockEnd == null) {
            throw new IllegalArgumentException("lock record fields must not be null");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("lock amount must be positive");
        }
        if (!lockEnd.isAfter(lockStart)) {
            throw new IllegalArgumentException("lockEnd must be after lockStart");
        }
        this.voter = voter;
        this.amount = amount;
        this.lockStart = lockStart;
        this.lockEnd = lockEnd;
    }

    public String getVoter() {
        return voter;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getLockStart() {
        return lockStart;
    }

    public Instant getLockEnd() {
        return lockEnd;
    }

    /**
     * 锁仓总时长。
     *
     * @return 锁仓时长
     */
    public Duration getDuration() {
        return Duration.between(lockStart, lockEnd);
    }

    /**
     * 判断指定时间是否已过锁仓到期时间。
     *
     * @param now 当前时间
     * @return 已解锁返回 true
     */
    public boolean isMatured(Instant now) {
        return now != null && !now.isBefore(lockEnd);
    }
}