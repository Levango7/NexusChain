package org.nexus.l2.challenge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 挑战期动态策略（高价值批次延长 + 可疑行为延长）。
 *
 * <p>基础挑战期由 {@code baseWindow} 给定（默认 7 天，符合 Optimistic Rollup 7 天挑战窗口惯例）。
 * 在以下两种情形下自动延长：</p>
 * <ul>
 *   <li><b>高价值批次</b>：批次金额超过 {@code highValueThreshold} 时，按
 *       {@code extensionPerTier × tier} 延长，tier = floor(log10(value / threshold))。
 *       例如阈值 100万、extensionPerTier=1 天：金额 100万→+0 天，1000万→+1 天，1亿→+2 天。
 *       上限 {@code maxExtension}（默认 30 天），防止无限延长。</li>
 *   <li><b>可疑行为</b>：检测到 sequencer 提交多个冲突 state root、
 *       隐藏交易、伪造 Merkle 证明等可疑行为时，每次延长 {@code suspiciousExtension}
 *       （默认 7 天），累计上限 {@code maxSuspiciousExtension}（默认 14 天）。</li>
 * </ul>
 *
 * <p>设计动机：高价值批次吸引更多挑战者关注，需更长窗口确保安全；
 * 可疑行为触发延长给予社区更多时间调查，提高欺诈发现概率。</p>
 *
 * @since 1.3
 */
@Component
public class ChallengePeriodPolicy {

    private static final Logger logger = LoggerFactory.getLogger(ChallengePeriodPolicy.class);

    /** 默认基础挑战期：7 天 */
    public static final Duration DEFAULT_BASE_WINDOW = Duration.ofDays(7);

    /** 默认高价值阈值：100 万（单位与 batch 金额一致，由调用方约定） */
    public static final BigDecimal DEFAULT_HIGH_VALUE_THRESHOLD = new BigDecimal("1000000");

    /** 默认每 tier 延长：1 天 */
    public static final Duration DEFAULT_EXTENSION_PER_TIER = Duration.ofDays(1);

    /** 默认高价值延长上限：30 天 */
    public static final Duration DEFAULT_MAX_EXTENSION = Duration.ofDays(30);

    /** 默认可疑行为单次延长：7 天 */
    public static final Duration DEFAULT_SUSPICIOUS_EXTENSION = Duration.ofDays(7);

    /** 默认可疑行为累计延长上限：14 天 */
    public static final Duration DEFAULT_MAX_SUSPICIOUS_EXTENSION = Duration.ofDays(14);

    /** 基础挑战期 */
    private final Duration baseWindow;

    /** 高价值阈值 */
    private final BigDecimal highValueThreshold;

    /** 每 tier 延长 */
    private final Duration extensionPerTier;

    /** 高价值延长上限 */
    private final Duration maxExtension;

    /** 可疑行为单次延长 */
    private final Duration suspiciousExtension;

    /** 可疑行为累计延长上限 */
    private final Duration maxSuspiciousExtension;

    /** 批次 ID -> 可疑行为延长累计 */
    private final Map<Long, Duration> suspiciousExtensions = new ConcurrentHashMap<>();

    /** 批次 ID -> 可疑行为原因记录 */
    private final Map<Long, List<String>> suspiciousReasons = new ConcurrentHashMap<>();

    /** 批次 ID -> 显式覆盖挑战期（如 governance 手动延长） */
    private final Map<Long, Duration> manualOverrides = new ConcurrentHashMap<>();

    public ChallengePeriodPolicy() {
        this(DEFAULT_BASE_WINDOW, DEFAULT_HIGH_VALUE_THRESHOLD, DEFAULT_EXTENSION_PER_TIER,
                DEFAULT_MAX_EXTENSION, DEFAULT_SUSPICIOUS_EXTENSION, DEFAULT_MAX_SUSPICIOUS_EXTENSION);
    }

    public ChallengePeriodPolicy(Duration baseWindow, BigDecimal highValueThreshold,
                                  Duration extensionPerTier, Duration maxExtension,
                                  Duration suspiciousExtension, Duration maxSuspiciousExtension) {
        this.baseWindow = baseWindow;
        this.highValueThreshold = highValueThreshold;
        this.extensionPerTier = extensionPerTier;
        this.maxExtension = maxExtension;
        this.suspiciousExtension = suspiciousExtension;
        this.maxSuspiciousExtension = maxSuspiciousExtension;
    }

    /**
     * 计算批次动态挑战期（含高价值延长 + 可疑行为延长）。
     *
     * @param batchId    批次 ID
     * @param batchValue 批次金额（null 视为 0）
     * @return 动态挑战期
     */
    public Duration computeChallengePeriod(long batchId, BigDecimal batchValue) {
        Duration base = baseWindow;
        // 1. 高价值延长
        Duration highValueExt = computeHighValueExtension(batchValue);
        // 2. 可疑行为延长
        Duration suspiciousExt = suspiciousExtensions.getOrDefault(batchId, Duration.ZERO);
        // 3. 显式覆盖
        Duration override = manualOverrides.get(batchId);

        Duration total = override != null ? override : base.plus(highValueExt).plus(suspiciousExt);
        logger.debug("Challenge period for batch {}: base={} + highValueExt={} + suspiciousExt={} = {}",
                batchId, base, highValueExt, suspiciousExt, total);
        return total;
    }

    /**
     * 计算批次动态挑战期（基于 BigInteger 金额）。
     *
     * @param batchId    批次 ID
     * @param batchValue 批次金额
     * @return 动态挑战期
     */
    public Duration computeChallengePeriod(long batchId, BigInteger batchValue) {
        return computeChallengePeriod(batchId, batchValue == null ? BigDecimal.ZERO : new BigDecimal(batchValue));
    }

    /**
     * 计算高价值延长（不包含可疑行为延长）。
     *
     * @param batchValue 批次金额
     * @return 高价值延长时长
     */
    public Duration computeHighValueExtension(BigDecimal batchValue) {
        if (batchValue == null || batchValue.signum() <= 0
                || highValueThreshold == null || highValueThreshold.signum() <= 0) {
            return Duration.ZERO;
        }
        if (batchValue.compareTo(highValueThreshold) < 0) {
            return Duration.ZERO;
        }
        // tier = floor(log10(value / threshold))
        BigDecimal ratio = batchValue.divide(highValueThreshold, 10, java.math.RoundingMode.HALF_UP);
        int tier = (int) Math.floor(Math.log10(ratio.doubleValue()));
        if (tier <= 0) {
            return Duration.ZERO;
        }
        Duration ext = extensionPerTier.multipliedBy(tier);
        if (ext.compareTo(maxExtension) > 0) {
            ext = maxExtension;
        }
        return ext;
    }

    /**
     * 报告可疑行为，延长批次挑战期。
     *
     * @param batchId 批次 ID
     * @param reason  可疑行为描述
     * @return 累计可疑行为延长
     */
    public synchronized Duration reportSuspiciousActivity(long batchId, String reason) {
        Duration current = suspiciousExtensions.getOrDefault(batchId, Duration.ZERO);
        Duration newTotal = current.plus(suspiciousExtension);
        if (newTotal.compareTo(maxSuspiciousExtension) > 0) {
            newTotal = maxSuspiciousExtension;
        }
        suspiciousExtensions.put(batchId, newTotal);
        suspiciousReasons.computeIfAbsent(batchId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(reason);
        logger.warn("Suspicious activity reported for batch {}: reason={}, suspiciousExt={} (capped at {})",
                batchId, reason, newTotal, maxSuspiciousExtension);
        return newTotal;
    }

    /**
     * 手动覆盖批次挑战期（governance 紧急延长）。
     *
     * @param batchId  批次 ID
     * @param override 新的挑战期
     */
    public void overrideChallengePeriod(long batchId, Duration override) {
        if (override == null || override.isNegative() || override.isZero()) {
            return;
        }
        manualOverrides.put(batchId, override);
        logger.info("Challenge period for batch {} manually overridden to {}", batchId, override);
    }

    /**
     * 判断批次挑战窗口是否已结束。
     *
     * @param batchId    批次 ID
     * @param submitTime 批次提交时间
     * @param batchValue 批次金额
     * @return 窗口已结束返回 true
     */
    public boolean isChallengeWindowOver(long batchId, Instant submitTime, BigDecimal batchValue) {
        if (submitTime == null) {
            return false;
        }
        Duration window = computeChallengePeriod(batchId, batchValue);
        return Instant.now().isAfter(submitTime.plus(window));
    }

    /**
     * 计算批次剩余挑战时间。
     *
     * @param batchId    批次 ID
     * @param submitTime 批次提交时间
     * @param batchValue 批次金额
     * @return 剩余挑战时长；已结束返回 Duration.ZERO
     */
    public Duration remainingChallengeTime(long batchId, Instant submitTime, BigDecimal batchValue) {
        if (submitTime == null) {
            return computeChallengePeriod(batchId, batchValue);
        }
        Duration window = computeChallengePeriod(batchId, batchValue);
        Instant deadline = submitTime.plus(window);
        Duration remaining = Duration.between(Instant.now(), deadline);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * 获取批次可疑行为原因列表。
     *
     * @param batchId 批次 ID
     * @return 原因列表
     */
    public List<String> getSuspiciousReasons(long batchId) {
        List<String> list = suspiciousReasons.get(batchId);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 获取批次累计可疑行为延长。
     *
     * @param batchId 批次 ID
     * @return 累计延长；无返回 Duration.ZERO
     */
    public Duration getSuspiciousExtension(long batchId) {
        return suspiciousExtensions.getOrDefault(batchId, Duration.ZERO);
    }

    /**
     * 清除批次挑战期状态（批次 finalize 或回滚后调用）。
     *
     * @param batchId 批次 ID
     */
    public void clear(long batchId) {
        suspiciousExtensions.remove(batchId);
        suspiciousReasons.remove(batchId);
        manualOverrides.remove(batchId);
    }

    public Duration getBaseWindow() {
        return baseWindow;
    }

    public BigDecimal getHighValueThreshold() {
        return highValueThreshold;
    }

    public Duration getExtensionPerTier() {
        return extensionPerTier;
    }

    public Duration getMaxExtension() {
        return maxExtension;
    }

    public Duration getSuspiciousExtension() {
        return suspiciousExtension;
    }

    public Duration getMaxSuspiciousExtension() {
        return maxSuspiciousExtension;
    }
}