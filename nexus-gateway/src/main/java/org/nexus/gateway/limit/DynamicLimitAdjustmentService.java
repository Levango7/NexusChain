package org.nexus.gateway.limit;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * 动态限额调整服务。
 *
 * <p>基于交易行为自动调整商户限额。支持三种调整规则：</p>
 * <ul>
 *   <li><b>CONSECUTIVE_SUCCESS</b>：连续成功交易达到阈值后提升限额</li>
 *   <li><b>HIGH_REFUND_RATE</b>：退款率超过阈值后降低限额</li>
 *   <li><b>RISK_EVENT</b>：风险事件触发后降低限额</li>
 * </ul>
 *
 * <p>调整流程：</p>
 * <ol>
 *   <li>获取商户的活跃调整规则</li>
 *   <li>根据规则类型评估是否触发调整条件</li>
 *   <li>计算调整后的限额值（考虑调整比例和上限/下限）</li>
 *   <li>更新 MerchantLimitConfig</li>
 *   <li>记录调整历史到 LimitAdjustmentRecord</li>
 * </ol>
 */
@Service
public class DynamicLimitAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(DynamicLimitAdjustmentService.class);

    private final LimitAdjustmentRuleRepository ruleRepository;
    private final LimitAdjustmentRecordRepository recordRepository;
    private final MerchantLimitConfigRepository limitConfigRepository;
    private final PaymentOrderRepository paymentOrderRepository;

    public DynamicLimitAdjustmentService(LimitAdjustmentRuleRepository ruleRepository,
                                          LimitAdjustmentRecordRepository recordRepository,
                                          MerchantLimitConfigRepository limitConfigRepository,
                                          PaymentOrderRepository paymentOrderRepository) {
        this.ruleRepository = ruleRepository;
        this.recordRepository = recordRepository;
        this.limitConfigRepository = limitConfigRepository;
        this.paymentOrderRepository = paymentOrderRepository;
    }

    /**
     * 评估并执行商户的限额调整规则。
     *
     * @param merchantId 商户 ID
     * @return 执行的调整记录列表（可能为空，表示无调整触发）
     */
    @Transactional
    public List<LimitAdjustmentRecord> evaluateAndAdjust(Long merchantId) {
        List<LimitAdjustmentRule> rules = ruleRepository.findByMerchantIdAndActiveTrue(merchantId);

        if (rules.isEmpty()) {
            log.debug("No active adjustment rules for merchantId={}", merchantId);
            return List.of();
        }

        Optional<MerchantLimitConfig> configOpt = limitConfigRepository.findByMerchantId(merchantId);
        if (configOpt.isEmpty()) {
            log.debug("No limit config for merchantId={}", merchantId);
            return List.of();
        }

        MerchantLimitConfig config = configOpt.get();
        List<LimitAdjustmentRecord> records = new java.util.ArrayList<>();

        for (LimitAdjustmentRule rule : rules) {
            boolean triggered = false;
            String reason = null;

            switch (rule.getRuleType()) {
                case CONSECUTIVE_SUCCESS:
                    int consecutiveSuccess = countConsecutiveSuccessTransactions(merchantId);
                    if (consecutiveSuccess >= rule.getTriggerThreshold()) {
                        triggered = true;
                        reason = "Consecutive success transactions: " + consecutiveSuccess
                                + " >= threshold " + rule.getTriggerThreshold();
                    }
                    break;

                case HIGH_REFUND_RATE:
                    BigDecimal refundRate = calculateRefundRate(merchantId);
                    BigDecimal thresholdRate = new BigDecimal(rule.getTriggerThreshold());
                    if (refundRate.compareTo(thresholdRate) >= 0) {
                        triggered = true;
                        reason = "Refund rate: " + refundRate + "% >= threshold " + thresholdRate + "%";
                    }
                    break;

                case RISK_EVENT:
                    int riskEvents = countRiskEvents(merchantId);
                    if (riskEvents >= rule.getTriggerThreshold()) {
                        triggered = true;
                        reason = "Risk events: " + riskEvents + " >= threshold " + rule.getTriggerThreshold();
                    }
                    break;
            }

            if (triggered) {
                LimitAdjustmentRecord record = applyAdjustment(merchantId, rule, config, reason);
                if (record != null) {
                    records.add(record);
                }
            }
        }

        log.info("Dynamic limit adjustment for merchantId={}: {} adjustments applied",
                merchantId, records.size());
        return records;
    }

    /**
     * 应用限额调整。
     *
     * @param merchantId 商户 ID
     * @param rule       触发的调整规则
     * @param config     当前限额配置
     * @param reason     触发原因
     * @return 调整记录，如果无需调整则返回 null
     */
    private LimitAdjustmentRecord applyAdjustment(Long merchantId, LimitAdjustmentRule rule,
                                                   MerchantLimitConfig config, String reason) {
        BigDecimal oldValue = getCurrentLimitValue(config, rule.getTargetLimitType());

        if (oldValue == null) {
            log.debug("Target limit type {} is null for merchantId={}, skipping adjustment",
                    rule.getTargetLimitType(), merchantId);
            return null;
        }

        BigDecimal newValue = calculateNewValue(oldValue, rule);

        // 应用上限/下限约束
        if (rule.getAdjustmentCap() != null) {
            if (rule.getAdjustmentDirection() == LimitAdjustmentRule.AdjustmentDirection.INCREASE) {
                newValue = newValue.min(rule.getAdjustmentCap());
            } else {
                newValue = newValue.max(rule.getAdjustmentCap());
            }
        }

        // 如果新旧值相同，无需调整
        if (newValue.compareTo(oldValue) == 0) {
            log.debug("Adjustment results in same value for merchantId={}, skipping", merchantId);
            return null;
        }

        // 更新限额配置
        updateLimitValue(config, rule.getTargetLimitType(), newValue);
        limitConfigRepository.save(config);

        // 创建调整记录
        LimitAdjustmentRecord record = new LimitAdjustmentRecord();
        record.setMerchantId(merchantId);
        record.setRuleId(rule.getId());
        record.setRuleType(rule.getRuleType());
        record.setTargetLimitType(rule.getTargetLimitType());
        record.setOldValue(oldValue);
        record.setNewValue(newValue);
        record.setAdjustmentPercentage(rule.getAdjustmentPercentage());
        record.setAdjustmentDirection(rule.getAdjustmentDirection());
        record.setReason(reason);

        record = recordRepository.save(record);

        log.info("Applied limit adjustment: merchantId={}, ruleType={}, target={}, old={}, new={}, reason={}",
                merchantId, rule.getRuleType(), rule.getTargetLimitType(), oldValue, newValue, reason);
        return record;
    }

    /**
     * 计算调整后的限额值。
     *
     * @param oldValue  当前限额值
     * @param rule      调整规则
     * @return 调整后的限额值
     */
    private BigDecimal calculateNewValue(BigDecimal oldValue, LimitAdjustmentRule rule) {
        BigDecimal adjustmentAmount = oldValue
                .multiply(rule.getAdjustmentPercentage())
                .divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);

        if (rule.getAdjustmentDirection() == LimitAdjustmentRule.AdjustmentDirection.INCREASE) {
            return oldValue.add(adjustmentAmount);
        } else {
            return oldValue.subtract(adjustmentAmount);
        }
    }

    /**
     * 获取当前限额配置中对应维度的值。
     */
    private BigDecimal getCurrentLimitValue(MerchantLimitConfig config,
                                             LimitAdjustmentRule.TargetLimitType targetLimitType) {
        switch (targetLimitType) {
            case SINGLE_MAX:
                return config.getSingleTransactionMaxAmount();
            case DAILY_MAX:
                return config.getDailyAccumulatedMaxAmount();
            case MONTHLY_MAX:
                return config.getMonthlyAccumulatedMaxAmount();
            case ANNUAL_MAX:
                return config.getAnnualCumulativeLimit();
            default:
                return null;
        }
    }

    /**
     * 更新限额配置中对应维度的值。
     */
    private void updateLimitValue(MerchantLimitConfig config,
                                    LimitAdjustmentRule.TargetLimitType targetLimitType,
                                    BigDecimal newValue) {
        switch (targetLimitType) {
            case SINGLE_MAX:
                config.setSingleTransactionMaxAmount(newValue);
                break;
            case DAILY_MAX:
                config.setDailyAccumulatedMaxAmount(newValue);
                break;
            case MONTHLY_MAX:
                config.setMonthlyAccumulatedMaxAmount(newValue);
                break;
            case ANNUAL_MAX:
                config.setAnnualCumulativeLimit(newValue);
                break;
        }
    }

    /**
     * 统计商户连续成功交易次数（从最近一笔交易往前数，直到遇到非成功交易）。
     *
     * @param merchantId 商户 ID
     * @return 连续成功交易次数
     */
    private int countConsecutiveSuccessTransactions(Long merchantId) {
        List<PaymentOrder> orders = paymentOrderRepository.findByMerchantId(merchantId);
        int consecutive = 0;
        // 从最近一笔往前数
        for (int i = orders.size() - 1; i >= 0; i--) {
            if (orders.get(i).getStatus() == PaymentOrder.OrderStatus.PAID) {
                consecutive++;
            } else {
                break;
            }
        }
        return consecutive;
    }

    /**
     * 计算商户当月退款率。
     *
     * @param merchantId 商户 ID
     * @return 退款率（百分比，如 20 表示 20%）
     */
    private BigDecimal calculateRefundRate(Long merchantId) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);

        List<PaymentOrder> paidOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.PAID, monthStart, nextMonthStart);
        List<PaymentOrder> refundedOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.REFUNDED, monthStart, nextMonthStart);

        int total = paidOrders.size() + refundedOrders.size();
        if (total == 0) {
            return BigDecimal.ZERO;
        }

        return new BigDecimal(refundedOrders.size())
                .multiply(new BigDecimal("100"))
                .divide(new BigDecimal(total), 4, RoundingMode.HALF_UP);
    }

    /**
     * 统计商户当月风险事件次数。
     *
     * <p>当前简化实现：统计当月 FAILED 状态的订单数作为风险事件。</p>
     *
     * @param merchantId 商户 ID
     * @return 风险事件次数
     */
    private int countRiskEvents(Long merchantId) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        LocalDateTime nextMonthStart = monthStart.plusMonths(1);

        List<PaymentOrder> failedOrders = paymentOrderRepository
                .findByMerchantIdAndStatusAndPaidAtBetween(
                        merchantId, PaymentOrder.OrderStatus.FAILED, monthStart, nextMonthStart);

        return failedOrders.size();
    }

    /**
     * 查询商户的限额调整历史。
     *
     * @param merchantId 商户 ID
     * @return 调整记录列表
     */
    public List<LimitAdjustmentRecord> getAdjustmentHistory(Long merchantId) {
        return recordRepository.findByMerchantId(merchantId);
    }

    /**
     * 创建限额调整规则。
     *
     * @param rule 调整规则
     * @return 创建的规则
     */
    @Transactional
    public LimitAdjustmentRule createAdjustmentRule(LimitAdjustmentRule rule) {
        if (rule.getRuleType() == null) {
            throw new IllegalArgumentException("ruleType is required");
        }
        if (rule.getAdjustmentDirection() == null) {
            throw new IllegalArgumentException("adjustmentDirection is required");
        }
        if (rule.getTargetLimitType() == null) {
            throw new IllegalArgumentException("targetLimitType is required");
        }
        if (rule.getTriggerThreshold() == null || rule.getTriggerThreshold() <= 0) {
            throw new IllegalArgumentException("triggerThreshold must be > 0");
        }
        if (rule.getAdjustmentPercentage() == null
                || rule.getAdjustmentPercentage().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("adjustmentPercentage must be > 0");
        }

        // 校验规则类型与调整方向的一致性
        if (rule.getRuleType() == LimitAdjustmentRule.RuleType.CONSECUTIVE_SUCCESS
                && rule.getAdjustmentDirection() != LimitAdjustmentRule.AdjustmentDirection.INCREASE) {
            throw new IllegalArgumentException("CONSECUTIVE_SUCCESS rule must use INCREASE direction");
        }
        if ((rule.getRuleType() == LimitAdjustmentRule.RuleType.HIGH_REFUND_RATE
                || rule.getRuleType() == LimitAdjustmentRule.RuleType.RISK_EVENT)
                && rule.getAdjustmentDirection() != LimitAdjustmentRule.AdjustmentDirection.DECREASE) {
            throw new IllegalArgumentException(rule.getRuleType() + " rule must use DECREASE direction");
        }

        rule.setActive(true);
        return ruleRepository.save(rule);
    }
}