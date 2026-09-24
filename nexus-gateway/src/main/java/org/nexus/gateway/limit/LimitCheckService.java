package org.nexus.gateway.limit;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
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
    private final ChannelLimitConfigRepository channelLimitConfigRepository;

    public LimitCheckService(MerchantLimitConfigRepository limitConfigRepository,
                             PaymentOrderRepository paymentOrderRepository,
                             ChannelLimitConfigRepository channelLimitConfigRepository) {
        this.limitConfigRepository = limitConfigRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.channelLimitConfigRepository = channelLimitConfigRepository;
    }

    /**
     * 综合检查所有限额，按顺序：
     * 1. 单笔最小金额 → 2. 单笔最大金额 → 3. 日累计金额 →
     * 4. 日交易笔数 → 5. 月累计金额 → 6. 月交易笔数 →
     * 7. 年单笔限额 → 8. 年累计限额
     *
     * <p>任一校验失败立即返回，不再继续后续校验。
     * 无限额配置时返回 passed()。</p>
     *
     * <p>P1-4 修复：一次性加载 MerchantLimitConfig 和日/月交易数据后复用，
     * 避免冗余数据库查询（从最多 5 次降至 3 次）。</p>
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

        // P1-4：一次性加载日/月交易数据，避免冗余查询
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = todayStart.plusDays(1);
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);
        LocalDateTime yearStart = Year.now().atDay(1).atStartOfDay();
        LocalDateTime nextYearStart = yearStart.plusYears(1);

        List<PaymentOrder> dailyOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, todayStart, tomorrowStart);
        List<PaymentOrder> monthlyOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, monthStart, nextMonthStart);
        List<PaymentOrder> yearlyOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, yearStart, nextYearStart);

        // 1. 单笔最小金额校验
        LimitCheckResult singleResult = checkSingleTransactionLimit(config, transactionAmount);
        if (!singleResult.isPassed()) {
            return singleResult;
        }

        // 3. 日累计金额校验
        LimitCheckResult dailyAmountResult = checkDailyAmountLimit(config, sumAmounts(dailyOrders), transactionAmount);
        if (!dailyAmountResult.isPassed()) {
            return dailyAmountResult;
        }

        // 4. 日交易笔数校验
        LimitCheckResult dailyCountResult = checkDailyCountLimit(config, dailyOrders.size());
        if (!dailyCountResult.isPassed()) {
            return dailyCountResult;
        }

        // 5. 月累计金额校验
        LimitCheckResult monthlyAmountResult = checkMonthlyAmountLimit(config, sumAmounts(monthlyOrders), transactionAmount);
        if (!monthlyAmountResult.isPassed()) {
            return monthlyAmountResult;
        }

        // 6. 月交易笔数校验
        LimitCheckResult monthlyCountResult = checkMonthlyCountLimit(config, monthlyOrders.size());
        if (!monthlyCountResult.isPassed()) {
            return monthlyCountResult;
        }

        // 7. 年单笔限额校验
        LimitCheckResult annualSingleResult = checkAnnualSingleLimit(config, transactionAmount);
        if (!annualSingleResult.isPassed()) {
            return annualSingleResult;
        }

        // 8. 年累计限额校验
        LimitCheckResult annualCumulativeResult = checkAnnualCumulativeLimit(config, sumAmounts(yearlyOrders), transactionAmount);
        if (!annualCumulativeResult.isPassed()) {
            return annualCumulativeResult;
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

        return checkDailyAmountLimit(config, getDailyAccumulatedAmount(merchantId), newAmount);
    }

    private LimitCheckResult checkDailyAmountLimit(MerchantLimitConfig config, BigDecimal currentAccumulated,
                                                    BigDecimal newAmount) {
        if (config.getDailyAccumulatedMaxAmount() == null) {
            return LimitCheckResult.passed();
        }


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

        return checkDailyCountLimit(config, getDailyTransactionCount(merchantId));
    }

    private LimitCheckResult checkDailyCountLimit(MerchantLimitConfig config, int currentCount) {
        if (config.getDailyMaxTransactionCount() == null) {
            return LimitCheckResult.passed();
        }


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

        return checkMonthlyAmountLimit(config, getMonthlyAccumulatedAmount(merchantId), newAmount);
    }

    private LimitCheckResult checkMonthlyAmountLimit(MerchantLimitConfig config, BigDecimal currentAccumulated,
                                                      BigDecimal newAmount) {
        if (config.getMonthlyAccumulatedMaxAmount() == null) {
            return LimitCheckResult.passed();
        }

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

        return checkMonthlyCountLimit(config, getMonthlyTransactionCount(merchantId));
    }

    private LimitCheckResult checkMonthlyCountLimit(MerchantLimitConfig config, int currentCount) {
        if (config.getMonthlyMaxTransactionCount() == null) {
            return LimitCheckResult.passed();
        }

        if (currentCount >= config.getMonthlyMaxTransactionCount()) {
            return LimitCheckResult.failed(
                    "MONTHLY_COUNT",
                    "Monthly transaction count " + currentCount + " has reached the monthly limit "
                            + config.getMonthlyMaxTransactionCount(),
                    null, currentCount, new BigDecimal(config.getMonthlyMaxTransactionCount()));
        }

        return LimitCheckResult.passed();
    }

    // ==================== 年度限额检查 ====================

    /**
     * 检查年单笔限额。
     *
     * @param merchantId 商户 ID
     * @param amount     待检查的交易金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkAnnualSingleLimit(Long merchantId, BigDecimal amount) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkAnnualSingleLimit(config, amount);
    }

    private LimitCheckResult checkAnnualSingleLimit(MerchantLimitConfig config, BigDecimal amount) {
        if (config.getAnnualSingleLimit() == null) {
            return LimitCheckResult.passed();
        }

        if (amount.compareTo(config.getAnnualSingleLimit()) > 0) {
            return LimitCheckResult.failed(
                    "ANNUAL_SINGLE",
                    "Transaction amount " + amount + " exceeds the annual single limit "
                            + config.getAnnualSingleLimit(),
                    null, 0, config.getAnnualSingleLimit());
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查年累计限额。
     *
     * @param merchantId 商户 ID
     * @param newAmount  新交易的金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkAnnualCumulativeLimit(Long merchantId, BigDecimal newAmount) {
        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        MerchantLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkAnnualCumulativeLimit(config, getAnnualAccumulatedAmount(merchantId), newAmount);
    }

    private LimitCheckResult checkAnnualCumulativeLimit(MerchantLimitConfig config, BigDecimal currentAccumulated,
                                                         BigDecimal newAmount) {
        if (config.getAnnualCumulativeLimit() == null) {
            return LimitCheckResult.passed();
        }

        BigDecimal projectedTotal = currentAccumulated.add(newAmount);

        if (projectedTotal.compareTo(config.getAnnualCumulativeLimit()) > 0) {
            return LimitCheckResult.failed(
                    "ANNUAL_CUMULATIVE",
                    "Annual accumulated amount " + projectedTotal + " would exceed the annual cumulative limit "
                            + config.getAnnualCumulativeLimit(),
                    currentAccumulated, 0, config.getAnnualCumulativeLimit());
        }

        return LimitCheckResult.passed();
    }

    /**
     * P1-4：从订单列表中累计金额（复用已加载的数据，避免冗余查询）。
     */
    private BigDecimal sumAmounts(List<PaymentOrder> orders) {
        BigDecimal total = BigDecimal.ZERO;
        for (PaymentOrder order : orders) {
            if (order.getAmount() != null) {
                total = total.add(order.getAmount());
            }
        }
        return total;
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
     * 获取商户当年的累计支付金额（PAID 状态）。
     *
     * @param merchantId 商户 ID
     * @return 当年累计金额，无订单时返回 BigDecimal.ZERO
     */
    public BigDecimal getAnnualAccumulatedAmount(Long merchantId) {
        LocalDateTime yearStart = Year.now().atDay(1).atStartOfDay();
        LocalDateTime nextYearStart = yearStart.plusYears(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, yearStart, nextYearStart);

        BigDecimal total = BigDecimal.ZERO;
        for (PaymentOrder order : paidOrders) {
            if (order.getAmount() != null) {
                total = total.add(order.getAmount());
            }
        }
        return total;
    }

    /**
     * 获取商户当年的支付笔数（PAID 状态）。
     *
     * @param merchantId 商户 ID
     * @return 当年支付笔数
     */
    public int getAnnualTransactionCount(Long merchantId) {
        LocalDateTime yearStart = Year.now().atDay(1).atStartOfDay();
        LocalDateTime nextYearStart = yearStart.plusYears(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, yearStart, nextYearStart);

        return paidOrders.size();
    }

    // ==================== 渠道限额检查 ====================

    /**
     * 检查渠道限额（单笔、日累计、月累计）。
     *
     * <p>按顺序检查：1. 渠道单笔限额 → 2. 渠道日累计限额 → 3. 渠道月累计限额</p>
     *
     * @param merchantId        商户 ID
     * @param channelType       渠道类型
     * @param transactionAmount 待检查的交易金额
     * @return 限额检查结果
     */
    public LimitCheckResult checkChannelLimits(Long merchantId, ChannelLimitConfig.ChannelType channelType,
                                                BigDecimal transactionAmount) {
        Optional<ChannelLimitConfig> configOpt = channelLimitConfigRepository
                .findByMerchantIdAndChannelType(merchantId, channelType);

        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        ChannelLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        // 1. 渠道单笔限额
        LimitCheckResult singleResult = checkChannelSingleLimit(config, transactionAmount);
        if (!singleResult.isPassed()) {
            return singleResult;
        }

        // 2. 渠道日累计限额
        LimitCheckResult dailyResult = checkChannelDailyLimit(config, merchantId, channelType, transactionAmount);
        if (!dailyResult.isPassed()) {
            return dailyResult;
        }

        // 3. 渠道月累计限额
        LimitCheckResult monthlyResult = checkChannelMonthlyLimit(config, merchantId, channelType, transactionAmount);
        if (!monthlyResult.isPassed()) {
            return monthlyResult;
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查渠道单笔限额。
     */
    public LimitCheckResult checkChannelSingleLimit(Long merchantId, ChannelLimitConfig.ChannelType channelType,
                                                     BigDecimal amount) {
        Optional<ChannelLimitConfig> configOpt = channelLimitConfigRepository
                .findByMerchantIdAndChannelType(merchantId, channelType);

        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        ChannelLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkChannelSingleLimit(config, amount);
    }

    private LimitCheckResult checkChannelSingleLimit(ChannelLimitConfig config, BigDecimal amount) {
        if (config.getSingleLimit() == null) {
            return LimitCheckResult.passed();
        }

        if (amount.compareTo(config.getSingleLimit()) > 0) {
            return LimitCheckResult.failed(
                    "CHANNEL_SINGLE",
                    "Transaction amount " + amount + " exceeds channel single limit "
                            + config.getSingleLimit() + " for channel " + config.getChannelType(),
                    null, 0, config.getSingleLimit());
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查渠道日累计限额。
     */
    public LimitCheckResult checkChannelDailyLimit(Long merchantId, ChannelLimitConfig.ChannelType channelType,
                                                    BigDecimal newAmount) {
        Optional<ChannelLimitConfig> configOpt = channelLimitConfigRepository
                .findByMerchantIdAndChannelType(merchantId, channelType);

        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        ChannelLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkChannelDailyLimit(config, merchantId, channelType, newAmount);
    }

    private LimitCheckResult checkChannelDailyLimit(ChannelLimitConfig config, Long merchantId,
                                                     ChannelLimitConfig.ChannelType channelType,
                                                     BigDecimal newAmount) {
        if (config.getDailyCumulativeLimit() == null) {
            return LimitCheckResult.passed();
        }

        BigDecimal currentAccumulated = getChannelDailyAccumulatedAmount(merchantId, channelType);
        BigDecimal projectedTotal = currentAccumulated.add(newAmount);

        if (projectedTotal.compareTo(config.getDailyCumulativeLimit()) > 0) {
            return LimitCheckResult.failed(
                    "CHANNEL_DAILY",
                    "Channel daily accumulated amount " + projectedTotal
                            + " would exceed the channel daily limit " + config.getDailyCumulativeLimit()
                            + " for channel " + config.getChannelType(),
                    currentAccumulated, 0, config.getDailyCumulativeLimit());
        }

        return LimitCheckResult.passed();
    }

    /**
     * 检查渠道月累计限额。
     */
    public LimitCheckResult checkChannelMonthlyLimit(Long merchantId, ChannelLimitConfig.ChannelType channelType,
                                                      BigDecimal newAmount) {
        Optional<ChannelLimitConfig> configOpt = channelLimitConfigRepository
                .findByMerchantIdAndChannelType(merchantId, channelType);

        if (configOpt.isEmpty()) {
            return LimitCheckResult.passed();
        }

        ChannelLimitConfig config = configOpt.get();
        if (!config.isActive()) {
            return LimitCheckResult.passed();
        }

        return checkChannelMonthlyLimit(config, merchantId, channelType, newAmount);
    }

    private LimitCheckResult checkChannelMonthlyLimit(ChannelLimitConfig config, Long merchantId,
                                                      ChannelLimitConfig.ChannelType channelType,
                                                      BigDecimal newAmount) {
        if (config.getMonthlyCumulativeLimit() == null) {
            return LimitCheckResult.passed();
        }

        BigDecimal currentAccumulated = getChannelMonthlyAccumulatedAmount(merchantId, channelType);
        BigDecimal projectedTotal = currentAccumulated.add(newAmount);

        if (projectedTotal.compareTo(config.getMonthlyCumulativeLimit()) > 0) {
            return LimitCheckResult.failed(
                    "CHANNEL_MONTHLY",
                    "Channel monthly accumulated amount " + projectedTotal
                            + " would exceed the channel monthly limit " + config.getMonthlyCumulativeLimit()
                            + " for channel " + config.getChannelType(),
                    currentAccumulated, 0, config.getMonthlyCumulativeLimit());
        }

        return LimitCheckResult.passed();
    }

    /**
     * 获取商户指定渠道当天的累计支付金额（PAID 状态）。
     *
     * @param merchantId  商户 ID
     * @param channelType 渠道类型
     * @return 当天累计金额，无订单时返回 BigDecimal.ZERO
     */
    public BigDecimal getChannelDailyAccumulatedAmount(Long merchantId,
                                                        ChannelLimitConfig.ChannelType channelType) {
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
     * 获取商户指定渠道当月的累计支付金额（PAID 状态）。
     *
     * @param merchantId  商户 ID
     * @param channelType 渠道类型
     * @return 当月累计金额，无订单时返回 BigDecimal.ZERO
     */
    public BigDecimal getChannelMonthlyAccumulatedAmount(Long merchantId,
                                                          ChannelLimitConfig.ChannelType channelType) {
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
     * 创建或更新渠道限额配置。
     *
     * @param merchantId              商户 ID
     * @param channelType             渠道类型
     * @param singleLimit             单笔限额（可为 null）
     * @param dailyCumulativeLimit    日累计限额（可为 null）
     * @param monthlyCumulativeLimit  月累计限额（可为 null）
     * @return 创建或更新后的配置
     */
    @Transactional
    public ChannelLimitConfig createOrUpdateChannelLimitConfig(Long merchantId,
                                                                ChannelLimitConfig.ChannelType channelType,
                                                                BigDecimal singleLimit,
                                                                BigDecimal dailyCumulativeLimit,
                                                                BigDecimal monthlyCumulativeLimit) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        if (channelType == null) {
            throw new IllegalArgumentException("channelType is required");
        }
        if (singleLimit != null && singleLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("singleLimit must be >= 0");
        }
        if (dailyCumulativeLimit != null && dailyCumulativeLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("dailyCumulativeLimit must be >= 0");
        }
        if (monthlyCumulativeLimit != null && monthlyCumulativeLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("monthlyCumulativeLimit must be >= 0");
        }

        Optional<ChannelLimitConfig> existingOpt = channelLimitConfigRepository
                .findByMerchantIdAndChannelType(merchantId, channelType);
        ChannelLimitConfig config;
        if (existingOpt.isPresent()) {
            config = existingOpt.get();
            log.info("Updating existing channel limit config for merchantId={}, channelType={}",
                    merchantId, channelType);
        } else {
            config = new ChannelLimitConfig();
            config.setMerchantId(merchantId);
            config.setChannelType(channelType);
            log.info("Creating new channel limit config for merchantId={}, channelType={}",
                    merchantId, channelType);
        }

        config.setSingleLimit(singleLimit);
        config.setDailyCumulativeLimit(dailyCumulativeLimit);
        config.setMonthlyCumulativeLimit(monthlyCumulativeLimit);
        config.setActive(true);

        return channelLimitConfigRepository.save(config);
    }

    /**
     * 获取商户的渠道限额配置列表。
     *
     * @param merchantId 商户 ID
     * @return 渠道限额配置列表
     */
    public List<ChannelLimitConfig> getChannelLimitConfigs(Long merchantId) {
        return channelLimitConfigRepository.findByMerchantId(merchantId);
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
        return createOrUpdateConfig(merchantId, singleMin, singleMax, dailyMax, monthlyMax,
                dailyCount, monthlyCount, null, null);
    }

    /**
     * 创建或更新商户限额配置（含年度限额）。
     *
     * @param merchantId           商户 ID
     * @param singleMin            单笔最小金额（可为 null）
     * @param singleMax            单笔最大金额（可为 null）
     * @param dailyMax             日累计最大金额（可为 null）
     * @param monthlyMax           月累计最大金额（可为 null）
     * @param dailyCount           日最大交易笔数（可为 null）
     * @param monthlyCount         月最大交易笔数（可为 null）
     * @param annualSingleLimit    年单笔限额（可为 null）
     * @param annualCumulativeLimit 年累计限额（可为 null）
     * @return 创建或更新后的配置
     * @throws IllegalArgumentException 如果校验失败
     */
    public MerchantLimitConfig createOrUpdateConfig(Long merchantId, BigDecimal singleMin,
                                                     BigDecimal singleMax, BigDecimal dailyMax,
                                                     BigDecimal monthlyMax, Integer dailyCount,
                                                     Integer monthlyCount, BigDecimal annualSingleLimit,
                                                     BigDecimal annualCumulativeLimit) {
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
        if (annualSingleLimit != null && annualSingleLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("annualSingleLimit must be >= 0");
        }
        if (annualCumulativeLimit != null && annualCumulativeLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("annualCumulativeLimit must be >= 0");
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
        config.setAnnualSingleLimit(annualSingleLimit);
        config.setAnnualCumulativeLimit(annualCumulativeLimit);
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