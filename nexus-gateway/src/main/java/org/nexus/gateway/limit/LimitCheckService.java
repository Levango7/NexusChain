package org.nexus.gateway.limit;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 商户业务级限额检查服务。
 *
 * <p>提供单笔金额、日/月累计金额、日/月交易笔数的限额校验。
 * 任一校验失败立即返回，不再继续后续校验。无限额配置时返回 passed()。</p>
 *
 * <p>时间范围计算规则：</p>
 * <ul>
 *   <li>当天 = [今天0点, 明天0点)</li>
 *   <li>当月 = [本月1号0点, 下月1号0点)</li>
 * </ul>
 */
@Service
public class LimitCheckService {

    private static final Logger log = LoggerFactory.getLogger(LimitCheckService.class);

    private final MerchantLimitConfigRepository limitConfigRepository;
    private final PaymentOrderRepository paymentOrderRepository;

    public LimitCheckService(MerchantLimitConfigRepository limitConfigRepository,
                             PaymentOrderRepository paymentOrderRepository) {
        this.limitConfigRepository = limitConfigRepository;
        this.paymentOrderRepository = paymentOrderRepository;
    }

    /**
     * 综合检查所有限额，按顺序：
     * 1. 单笔最小金额 → 2. 单笔最大金额 → 3. 日累计金额 →
     * 4. 日交易笔数 → 5. 月累计金额 → 6. 月交易笔数
     *
     * <p>任一校验失败立即返回，不再继续后续校验。
     * 无限额配置时返回 passed()。</p>
     *
     * @param merchantId        商户 ID
     * @param transactionAmount 待检查的交易金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkLimits(Long merchantId, BigDecimal transactionAmount) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        // 1. 单笔最小金额校验
        LimitCheckResult singleResult = checkSingleTransactionLimit(config, transactionAmount);
        if (!singleResult.isPassed()) {
            return singleResult;
        }

        // 3. 日累计金额校验
        LimitCheckResult dailyAmountResult = checkDailyAmountLimit(config, merchantId, transactionAmount);
        if (!dailyAmountResult.isPassed()) {
            return dailyAmountResult;
        }

        // 4. 日交易笔数校验
        LimitCheckResult dailyCountResult = checkDailyCountLimit(config, merchantId);
        if (!dailyCountResult.isPassed()) {
            return dailyCountResult;
        }

        // 5. 月累计金额校验
        LimitCheckResult monthlyAmountResult = checkMonthlyAmountLimit(config, merchantId, transactionAmount);
        if (!monthlyAmountResult.isPassed()) {
            return monthlyAmountResult;
        }

        // 6. 月交易笔数校验
        LimitCheckResult monthlyCountResult = checkMonthlyCountLimit(config, merchantId);
        if (!monthlyCountResult.isPassed()) {
            return monthlyCountResult;
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查单笔最小/最大金额。
     *
     * @param merchantId 商户 ID
     * @param amount     待检查的交易金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkSingleTransactionLimit(Long merchantId, BigDecimal amount) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkSingleTransactionLimit(config, amount);
    }

    private LimitCheckResult checkSingleTransactionLimit(MerchantLimitConfig config, BigDecimal amount) {
        // 单笔最小金额校验
        if (config.getSingleTransactionMinAmount() != null) {
            if (amount.compareTo(config.getSingleTransactionMinAmount()) < 0) {
                return LimitCheckResult.failed(
                        "SINGLE_MIN",
                        "Transaction amount " + amount + " is below the minimum allowed "
                                + config.getSingleTransactionMinAmount(),
                        null, 0, config.getSingleTransactionMinAmount());
            }
        }

        // 单笔最大金额校验
        if (config.getSingleTransactionMaxAmount() != null) {
            if (amount.compareTo(config.getSingleTransactionMaxAmount()) > 0) {
                return LimitCheckResult.failed(
                        "SINGLE_MAX",
                        "Transaction amount " + amount + " exceeds the maximum allowed "
                                + config.getSingleTransactionMaxAmount(),
                        null, 0, config.getSingleTransactionMaxAmount());
            }
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查日累计金额限制。
     *
     * <p>累计金额 + newAmount > dailyAccumulatedMaxAmount 时拒绝。</p>
     *
     * @param merchantId 商户 ID
     * @param newAmount  新交易的金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkDailyAmountLimit(Long merchantId, BigDecimal newAmount) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkDailyAmountLimit(config, merchantId, newAmount);
    }

    private LimitCheckResult checkDailyAmountLimit(MerchantLimitConfig config, Long merchantId,
                                                    BigDecimal newAmount) {
        if (config.getDailyAccumulatedMaxAmount() == null) {
            return LimitCheckResult.passed();
        }

        BigDecimal currentAccumulated = getDailyAccumulatedAmount(merchantId);
        BigDecimal projectedTotal = currentAccumulated.add(newAmount);

        if (projectedTotal.compareTo(config.getDailyAccumulatedMaxAmount()) > 0) {
            return LimitCheckResult.failed(
                    "DAILY_AMOUNT",
                    "Daily accumulated amount " + projectedTotal + " would exceed the daily limit "
                            + config.getDailyAccumulatedMaxAmount(),
                    currentAccumulated, 0, config.getDailyAccumulatedMaxAmount());
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查日交易笔数限制。
     *
     * <p>笔数 >= dailyMaxTransactionCount 时拒绝。</p>
     *
     * @param merchantId 商户 ID
     * @return 限额检查结果
     */
    public LimitCheckResult checkDailyCountLimit(Long merchantId) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkDailyCountLimit(config, merchantId);
    }

    private LimitCheckResult checkDailyCountLimit(MerchantLimitConfig config, Long merchantId) {
        if (config.getDailyMaxTransactionCount() == null) {
            return LimitCheckResult.passed();
        }

        int currentCount = getDailyTransactionCount(merchantId);

        if (currentCount >= config.getDailyMaxTransactionCount()) {
            return LimitCheckResult.failed(
                    "DAILY_COUNT",
                    "Daily transaction count " + currentCount + " has reached the daily limit "
                            + config.getDailyMaxTransactionCount(),
                    null, currentCount, new BigDecimal(config.getDailyMaxTransactionCount()));
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查月累计金额限制。
     *
     * @param merchantId 商户 ID
     * @param newAmount  新交易的金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkMonthlyAmountLimit(Long merchantId, BigDecimal newAmount) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkMonthlyAmountLimit(config, merchantId, newAmount);
    }

    private LimitCheckResult checkMonthlyAmountLimit(MerchantLimitConfig config, Long merchantId,
                                                      BigDecimal newAmount) {
        if (config.getMonthlyAccumulatedMaxAmount() == null) {
            return LimitCheckResult.passed();
        }

        BigDecimal currentAccumulated = getMonthlyAccumulatedAmount(merchantId);
        BigDecimal projectedTotal = currentAccumulated.add(newAmount);

        if (projectedTotal.compareTo(config.getMonthlyAccumulatedMaxAmount()) > 0) {
            return LimitCheckResult.failed(
                    "MONTHLY_AMOUNT",
                    "Monthly accumulated amount " + projectedTotal + " would exceed the monthly limit "
                            + config.getMonthlyAccumulatedMaxAmount(),
                    currentAccumulated, 0, config.getMonthlyAccumulatedMaxAmount());
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查月交易笔数限制。
     *
     * @param merchantId 商户 ID
     * @return 限额检查结果
     */
    public LimitCheckResult checkMonthlyCountLimit(Long merchantId) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkMonthlyCountLimit(config, merchantId);
    }

    private LimitCheckResult checkMonthlyCountLimit(MerchantLimitConfig config, Long merchantId) {
        if (config.getMonthlyMaxTransactionCount() == null) {
            return LimitCheckResult.passed();
        }

        int currentCount = getMonthlyTransactionCount(merchantId);

        if (currentCount >= config.getMonthlyMaxTransactionCount()) {
            return LimitCheckResult.failed(
                    "MONTHLY_COUNT",
                    "Monthly transaction count " + currentCount + " has reached the monthly limit "
                            + config.getMonthlyMaxTransactionCount(),
                    null, currentCount, new BigDecimal(config.getMonthlyMaxTransactionCount()));
        }

        return LimitCheckResult.passed();
    }

    /**
     * 获取商户当天的累计支付金额（PAID 状态）。
     *
     * @param merchantId 商户 ID
     * @return 当天累计金额，无订单时返回 BigDecimal.ZERO
     */
    public BigDecimal getDailyAccumulatedAmount(Long merchantId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, todayStart, tomorrowStart);

        BigDecimal total = BigDecimal.ZERO;
        for (PaymentOrder order : paidOrders) {
            if (order.getAmount() != null) {
                total = total.add(order.getAmount());
            }
        }
        return total;
    }

    /**
     * 获取商户当天的支付笔数（PAID 状态）。
     *
     * @param merchantId 商户 ID
     * @return 当天支付笔数
     */
    public int getDailyTransactionCount(Long merchantId) {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, todayStart, tomorrowStart);

        return paidOrders.size();
    }

    /**
     * 获取商户当月的累计支付金额（PAID 状态）。
     *
     * @param merchantId 商户 ID
     * @return 当月累计金额，无订单时返回 BigDecimal.ZERO
     */
    public BigDecimal getMonthlyAccumulatedAmount(Long merchantId) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, monthStart, nextMonthStart);

        BigDecimal total = BigDecimal.ZERO;
        for (PaymentOrder order : paidOrders) {
            if (order.getAmount() != null) {
                total = total.add(order.getAmount());
            }
        }
        return total;
    }

    /**
     * 获取商户当月的支付笔数（PAID 状态）。
     *
     * @param merchantId 商户 ID
     * @return 当月支付笔数
     */
    public int getMonthlyTransactionCount(Long merchantId) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, monthStart, nextMonthStart);

        return paidOrders.size();
    }

    /**
     * 创建或更新商户限额配置。
     *
     * <p>校验规则：</p>
     * <ul>
     *   <li>singleMin <= singleMax（两者非 null 时）</li>
     *   <li>dailyMax >= 0</li>
     *   <li>monthlyMax >= 0</li>
     *   <li>dailyCount >= 0</li>
     *   <li>monthlyCount >= 0</li>
     * </ul>
     *
     * @param merchantId   商户 ID
     * @param singleMin    单笔最小金额（可为 null）
     * @param singleMax    单笔最大金额（可为 null）
     * @param dailyMax     日累计最大金额（可为 null）
     * @param monthlyMax   月累计最大金额（可为 null）
     * @param dailyCount   日最大交易笔数（可为 null）
     * @param monthlyCount 月最大交易笔数（可为 null）
     * @return 创建或更新后的配置
     * @throws IllegalArgumentException 如果校验失败
     */
    public MerchantLimitConfig createOrUpdateConfig(Long merchantId, BigDecimal singleMin,
                                                     BigDecimal singleMax, BigDecimal dailyMax,
                                                     BigDecimal monthlyMax, Integer dailyCount,
                                                     Integer monthlyCount) {
        // 校验 singleMin <= singleMax
        if (singleMin != null && singleMax != null && singleMin.compareTo(singleMax) > 0) {
            throw new IllegalArgumentException(
                    "singleTransactionMinAmount (" + singleMin + ") must be <= singleTransactionMaxAmount ("
                            + singleMax + ")");
        }

        // 校验非负值
        if (dailyMax != null && dailyMax.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dailyAccumulatedMaxAmount must be >= 0");
        }
        if (monthlyMax != null && monthlyMax.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("monthlyAccumulatedMaxAmount must be >= 0");
        }
        if (dailyCount != null && dailyCount < 0) {
            throw new IllegalArgumentException("dailyMaxTransactionCount must be >= 0");
        }
        if (monthlyCount != null && monthlyCount < 0) {
            throw new IllegalArgumentException("monthlyMaxTransactionCount must be >= 0");
        }

        Optional<MerchantLimitConfig> existingOpt = limitConfigRepository.findByMerchantId(merchantId);
        MerchantLimitConfig config;
        if (existingOpt.isPresent()) {
            config = existingOpt.get();
            log.info("Updating existing limit config for merchantId={}, id={}", merchantId, config.getId());
        } else {
            config = new MerchantLimitConfig();
            config.setMerchantId(merchantId);
            log.info("Creating new limit config for merchantId={}", merchantId);
        }

        config.setSingleTransactionMinAmount(singleMin);
        config.setSingleTransactionMaxAmount(singleMax);
        config.setDailyAccumulatedMaxAmount(dailyMax);
        config.setMonthlyAccumulatedMaxAmount(monthlyMax);
        config.setDailyMaxTransactionCount(dailyCount);
        config.setMonthlyMaxTransactionCount(monthlyCount);
        config.setActive(true);

        return limitConfigRepository.save(config);
    }

    /**
     * 获取商户限额配置。
     *
     * @param merchantId 商户 ID
     * @return 限额配置，无配置时返回 null
     */
    public MerchantLimitConfig getConfig(Long merchantId) {
        return limitConfigRepository.findByMerchantId(merchantId).orElse(null);
    }
}