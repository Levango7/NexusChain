package org.nexus.governance.delegation;

import org.nexus.consensus.pos.StakingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 投票权重计算器。
 *
 * <p>综合质押、委托、锁仓加权与时间衰减计算投票人在指定时刻的有效投票权重：</p>
 * <pre>
 *   votingPower(voter, now) = basePower + lockedBonus
 *
 *   basePower   = selfStake + delegatedStake          // 稳定基础权重，不衰减
 *   selfStake   = stakingService.getStake(voter)
 *   delegatedStake = Σ stakingService.getStake(d)      // 所有委托给 voter 的委托人质押之和
 *                     for d in delegationService.getDelegators(voter)
 *
 *   lockedBonus = Σ lock.amount × lockMultiplier(lock.duration) × lockDecay(lock, now)
 *
 *   lockMultiplier(duration) = 1 + min(duration / MAX_LOCK, 1) × (MAX_MULTIPLIER − 1)
 *       // 锁仓越久倍数越高：0 天 → 1×，满 MAX_LOCK(365d) → MAX_MULTIPLIER(4×)
 *
 *   lockDecay(lock, now) = sqrt( max(0, (lockEnd − now) / (lockEnd − lockStart)) )
 *       // sqrt 时间衰减：刚锁仓时 ≈1，临近解锁衰减，已解锁 → 0（加权失效）
 * </pre>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>锁仓加权</b>：锁仓期越长，{@code lockMultiplier} 越高，激励长期锁仓</li>
 *   <li><b>时间衰减</b>：未解锁锁仓的加权随时间 sqrt 衰减，已解锁锁仓加权归零，
 *       鼓励持续锁仓而非一次性锁仓后坐享权重</li>
 *   <li><b>委托加权</b>：受托人累计获得所有委托人的质押量作为额外基础权重</li>
 *   <li><b>基础权重不衰减</b>：自质押与委托质押构成稳定基础权重，仅锁仓部分有衰减</li>
 * </ul>
 *
 * @since 1.4
 */
@Component
public class VotingPowerCalculator {

    private static final Logger logger = LoggerFactory.getLogger(VotingPowerCalculator.class);

    /** 最大锁仓时长：365 天（毫秒） */
    private static final BigDecimal MAX_LOCK_DURATION_MS =
            new BigDecimal(Duration.ofDays(365).toMillis());

    /** 锁仓最高权重倍数 */
    private static final BigDecimal MAX_LOCK_MULTIPLIER = new BigDecimal("4");

    /** 倍数区间：MAX_MULTIPLIER − 1 = 3 */
    private static final BigDecimal MULTIPLIER_SPAN = MAX_LOCK_MULTIPLIER.subtract(BigDecimal.ONE);

    /** 高精度数学上下文 */
    private static final MathContext MATH = MathContext.DECIMAL128;

    @Autowired
    private StakingService stakingService;

    @Autowired
    private DelegationService delegationService;

    /** voter -> 锁仓记录列表 */
    private final ConcurrentHashMap<String, List<LockRecord>> locks = new ConcurrentHashMap<>();

    /**
     * 登记一条锁仓记录。
     *
     * @param voter     锁仓人
     * @param amount    锁仓金额
     * @param lockStart 锁仓起始时间
     * @param lockEnd   锁仓到期时间
     * @return 登记成功返回 true；参数非法返回 false
     */
    public boolean lock(String voter, BigDecimal amount, Instant lockStart, Instant lockEnd) {
        if (voter == null) {
            return false;
        }
        try {
            LockRecord record = new LockRecord(voter, amount, lockStart, lockEnd);
            locks.computeIfAbsent(voter, k -> new CopyOnWriteArrayList<>()).add(record);
            logger.info("Lock registered: voter={} amount={} until={}", voter, amount, lockEnd);
            return true;
        } catch (IllegalArgumentException e) {
            logger.warn("Lock rejected: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查询指定投票人的所有锁仓记录（只读）。
     *
     * @param voter 投票人
     * @return 锁仓记录列表；无记录返回空列表
     */
    public List<LockRecord> getLocks(String voter) {
        if (voter == null) {
            return Collections.emptyList();
        }
        List<LockRecord> list = locks.get(voter);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * 计算指定投票人在指定时刻的有效投票权重。
     *
     * @param voter 投票人
     * @param now   当前时间
     * @return 投票权重；参数非法返回 {@link BigDecimal#ZERO}
     */
    public BigDecimal calculateVotingPower(String voter, Instant now) {
        if (voter == null || now == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal selfStake = stakingService.getStake(voter);
        if (selfStake == null || selfStake.signum() < 0) {
            selfStake = BigDecimal.ZERO;
        }
        BigDecimal delegatedStake = computeDelegatedStake(voter);
        BigDecimal basePower = selfStake.add(delegatedStake);

        BigDecimal lockedBonus = computeLockedBonus(voter, now);
        BigDecimal total = basePower.add(lockedBonus);

        logger.debug("VotingPower voter={} self={} delegated={} lockedBonus={} total={}",
                voter, selfStake, delegatedStake, lockedBonus, total);
        return total;
    }

    /**
     * 计算委托人委托给指定受托人的质押量之和。
     *
     * @param delegatee 受托人
     * @return 委托质押之和
     */
    private BigDecimal computeDelegatedStake(String delegatee) {
        Set<String> delegators = delegationService.getDelegators(delegatee);
        if (delegators.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (String delegator : delegators) {
            BigDecimal stake = stakingService.getStake(delegator);
            if (stake != null && stake.signum() > 0) {
                sum = sum.add(stake);
            }
        }
        return sum;
    }

    /**
     * 计算锁仓加权与时间衰减后的锁仓权重红利。
     *
     * @param voter 投票人
     * @param now   当前时间
     * @return 锁仓权重红利
     */
    private BigDecimal computeLockedBonus(String voter, Instant now) {
        List<LockRecord> list = locks.get(voter);
        if (list == null || list.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal bonus = BigDecimal.ZERO;
        for (LockRecord lock : list) {
            if (lock.isMatured(now)) {
                // 已解锁锁仓加权失效
                continue;
            }
            BigDecimal multiplier = lockMultiplier(lock.getDuration());
            BigDecimal decay = lockDecay(lock, now);
            BigDecimal contribution = lock.getAmount().multiply(multiplier, MATH).multiply(decay, MATH);
            bonus = bonus.add(contribution);
        }
        return bonus;
    }

    /**
     * 锁仓加权倍数：{@code 1 + min(duration / MAX_LOCK, 1) × (MAX_MULTIPLIER − 1)}。
     *
     * <p>锁仓 0 天 → 1×，锁仓满 365 天 → 4×，超过 365 天封顶 4×。</p>
     *
     * @param duration 锁仓时长
     * @return 加权倍数
     */
    BigDecimal lockMultiplier(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return BigDecimal.ONE;
        }
        BigDecimal durationMs = new BigDecimal(duration.toMillis());
        BigDecimal ratio = durationMs.divide(MAX_LOCK_DURATION_MS, MATH);
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }
        return BigDecimal.ONE.add(ratio.multiply(MULTIPLIER_SPAN, MATH), MATH);
    }

    /**
     * 锁仓时间衰减因子：{@code sqrt(max(0, (lockEnd − now) / (lockEnd − lockStart)))}。
     *
     * <p>刚锁仓时剩余比例 ≈1，衰减因子 ≈1；临近解锁剩余比例趋近 0，衰减因子趋近 0；
     * 已解锁返回 0。</p>
     *
     * @param lock 锁仓记录
     * @param now  当前时间
     * @return 衰减因子（0 ~ 1）
     */
    BigDecimal lockDecay(LockRecord lock, Instant now) {
        if (lock.isMatured(now)) {
            return BigDecimal.ZERO;
        }
        BigDecimal remainingMs = new BigDecimal(Duration.between(now, lock.getLockEnd()).toMillis());
        BigDecimal totalMs = new BigDecimal(lock.getDuration().toMillis());
        if (totalMs.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = remainingMs.divide(totalMs, MATH);
        if (ratio.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (ratio.compareTo(BigDecimal.ONE) > 0) {
            ratio = BigDecimal.ONE;
        }
        return ratio.sqrt(MATH);
    }

    /**
     * 列出所有有锁仓记录的投票人（只读）。
     *
     * @return 投票人列表
     */
    public List<String> getLockHolders() {
        return new ArrayList<>(locks.keySet());
    }
}