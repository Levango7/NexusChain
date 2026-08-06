package org.nexus.consensus.pos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 质押服务默认实现。
 *
 * <p>提供质押 / 解质押 / 提取 / 奖励计算能力。解质押进入
 * 锁定周期，到期后方可提取。奖励按年化收益率计算。</p>
 *
 * @since 1.2
 */
@Component
public class StakingServiceImpl implements StakingService {

    private static final Logger logger = LoggerFactory.getLogger(StakingServiceImpl.class);

    /** 默认锁定周期：7 天（秒） */
    private static final long DEFAULT_LOCK_PERIOD_SECONDS = 7L * 24 * 3600;

    /** 默认年化奖励率：5% */
    private static final BigDecimal DEFAULT_ANNUAL_REWARD_RATE = new BigDecimal("0.05");

    @Autowired
    private ValidatorRegistry validatorRegistry;

    private final long lockPeriodSeconds;
    private final BigDecimal annualRewardRate;

    /** 验证人地址 -> 当前质押金额 */
    private final Map<String, BigDecimal> stakes = new ConcurrentHashMap<>();

    /** 验证人地址 -> 待提取队列 */
    private final Map<String, List<UnstakeEntry>> unstakingQueue = new ConcurrentHashMap<>();

    public StakingServiceImpl() {
        this(DEFAULT_LOCK_PERIOD_SECONDS, DEFAULT_ANNUAL_REWARD_RATE);
    }

    public StakingServiceImpl(long lockPeriodSeconds, BigDecimal annualRewardRate) {
        this.lockPeriodSeconds = lockPeriodSeconds;
        this.annualRewardRate = annualRewardRate;
    }

    @Override
    public void stake(String validator, BigDecimal amount) {
        if (validator == null || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid stake request: validator=" + validator + ", amount=" + amount);
        }
        stakes.merge(validator, amount, BigDecimal::add);
        Validator v = validatorRegistry.getValidator(validator);
        if (v != null) {
            v.setStakeAmount(v.getStakeAmount().add(amount));
        }
        logger.info("Staked {} to {}", amount, validator);
    }

    @Override
    public void unstake(String validator, BigDecimal amount) {
        if (validator == null || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid unstake request");
        }
        BigDecimal current = stakes.getOrDefault(validator, BigDecimal.ZERO);
        if (amount.compareTo(current) > 0) {
            throw new IllegalArgumentException("Unstake amount " + amount + " exceeds current stake " + current);
        }
        stakes.merge(validator, amount.negate(), BigDecimal::add);
        Instant unlockTime = Instant.now().plusSeconds(lockPeriodSeconds);
        unstakingQueue.computeIfAbsent(validator, k -> new ArrayList<>()).add(new UnstakeEntry(amount, unlockTime));
        Validator v = validatorRegistry.getValidator(validator);
        if (v != null) {
            v.setStakeAmount(v.getStakeAmount().subtract(amount));
        }
        logger.info("Unstaked {} from {}, unlock at {}", amount, validator, unlockTime);
    }

    /**
     * 提取已解锁的质押金额。
     *
     * @param validator 验证人地址
     * @return 实际提取金额（未到期部分不可提取）
     */
    public BigDecimal withdraw(String validator) {
        List<UnstakeEntry> queue = unstakingQueue.get(validator);
        if (queue == null || queue.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Instant now = Instant.now();
        BigDecimal withdrawable = BigDecimal.ZERO;
        Iterator<UnstakeEntry> it = queue.iterator();
        while (it.hasNext()) {
            UnstakeEntry entry = it.next();
            if (now.isAfter(entry.unlockTime)) {
                withdrawable = withdrawable.add(entry.amount);
                it.remove();
            }
        }
        if (withdrawable.signum() > 0) {
            logger.info("Withdrew {} from {}", withdrawable, validator);
        }
        return withdrawable;
    }

    @Override
    public BigDecimal getStake(String validator) {
        return stakes.getOrDefault(validator, BigDecimal.ZERO);
    }

    @Override
    public void distributeRewards() {
        List<Validator> active = validatorRegistry.getActiveValidators();
        for (Validator v : active) {
            BigDecimal reward = v.getStakeAmount().multiply(annualRewardRate);
            if (reward.signum() > 0) {
                stakes.merge(v.getAddress(), reward, BigDecimal::add);
                v.setStakeAmount(v.getStakeAmount().add(reward));
                logger.info("Distributed reward {} to {}", reward, v.getAddress());
            }
        }
    }

    /**
     * 查询指定验证人待提取队列中已到期但未提取的金额。
     *
     * @param validator 验证人地址
     * @return 可提取金额
     */
    public BigDecimal getWithdrawable(String validator) {
        List<UnstakeEntry> queue = unstakingQueue.get(validator);
        if (queue == null) {
            return BigDecimal.ZERO;
        }
        Instant now = Instant.now();
        BigDecimal sum = BigDecimal.ZERO;
        for (UnstakeEntry entry : queue) {
            if (now.isAfter(entry.unlockTime)) {
                sum = sum.add(entry.amount);
            }
        }
        return sum;
    }

    public long getLockPeriodSeconds() {
        return lockPeriodSeconds;
    }

    public BigDecimal getAnnualRewardRate() {
        return annualRewardRate;
    }

    /** 解质押条目 */
    private static final class UnstakeEntry {
        final BigDecimal amount;
        final Instant unlockTime;

        UnstakeEntry(BigDecimal amount, Instant unlockTime) {
            this.amount = amount;
            this.unlockTime = unlockTime;
        }
    }
}