package org.nexus.gateway.split;

import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 分账/分润核心服务。
 *
 * <p>负责分账规则的创建、查询、停用，以及分账金额的计算与持久化。</p>
 *
 * <h3>分账计算逻辑</h3>
 * <ol>
 *   <li>获取商户所有活跃规则（按 priority 升序）</li>
 *   <li>FIXED 规则先执行：直接扣除固定金额</li>
 *   <li>RATIO 规则后执行：对剩余金额按比例分配</li>
 *   <li>校验：所有分账金额之和 ≤ totalAmount</li>
 *   <li>无规则时返回空列表（不分账，全额结算给商户）</li>
 * </ol>
 */
@Service
public class SplitService {

    private static final Logger log = LoggerFactory.getLogger(SplitService.class);

    /** 基点上限：10000 = 100% */
    private static final BigDecimal MAX_BASIS_POINTS = new BigDecimal("10000");

    /** FIXED 规则的默认优先级 */
    private static final int FIXED_PRIORITY = 0;

    /** RATIO 规则的默认优先级 */
    private static final int RATIO_PRIORITY = 10;

    private final SplitRuleRepository splitRuleRepository;
    private final SplitOrderRepository splitOrderRepository;
    private final TieredSplitRuleRepository tieredSplitRuleRepository;
    private final MerchantOwnershipGuard ownershipGuard;

    public SplitService(SplitRuleRepository splitRuleRepository,
                        SplitOrderRepository splitOrderRepository,
                        TieredSplitRuleRepository tieredSplitRuleRepository,
                        MerchantOwnershipGuard ownershipGuard) {
        this.splitRuleRepository = splitRuleRepository;
        this.splitOrderRepository = splitOrderRepository;
        this.tieredSplitRuleRepository = tieredSplitRuleRepository;
        this.ownershipGuard = ownershipGuard;
    }

    // ==================== 分账规则管理 ====================

    /**
     * 创建分账规则。
     *
     * <p>校验逻辑：</p>
     * <ul>
     *   <li>RATIO 模式：splitValue 必须在 1-10000 范围内（基点）</li>
     *   <li>FIXED 模式：splitValue 必须大于 0</li>
     *   <li>同一商户的 RATIO 规则总和不能超过 10000 基点</li>
     * </ul>
     *
     * <p>自动设置优先级：FIXED 规则 priority=0，RATIO 规则 priority=10</p>
     *
     * @param merchantId      商户 ID
     * @param type            分账类型
     * @param value           分账值
     * @param receiverAddress 接收地址
     * @param description     规则描述（可选）
     * @return 创建的 SplitRule
     * @throws IllegalArgumentException 如果参数校验失败
     */
    @Transactional
    public SplitRule createSplitRule(Long merchantId, SplitRule.SplitType type, BigDecimal value,
                                     String receiverAddress, String description) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("splitType is required");
        }
        if (value == null) {
            throw new IllegalArgumentException("splitValue is required");
        }
        if (receiverAddress == null || receiverAddress.isBlank()) {
            throw new IllegalArgumentException("receiverAddress is required");
        }

        // 类型相关校验
        if (type == SplitRule.SplitType.RATIO) {
            // RATIO 模式：value 必须在 1-10000 范围内
            if (value.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("RATIO splitValue must be >= 1 (basis points)");
            }
            if (value.compareTo(MAX_BASIS_POINTS) > 0) {
                throw new IllegalArgumentException("RATIO splitValue must be <= 10000 (basis points)");
            }
            // 校验同一商户的 RATIO 规则总和不超过 10000
            List<SplitRule> existingRules = splitRuleRepository
                    .findByMerchantIdAndActiveTrueOrderByPriorityAsc(merchantId);
            BigDecimal totalRatio = BigDecimal.ZERO;
            for (SplitRule rule : existingRules) {
                if (rule.getSplitType() == SplitRule.SplitType.RATIO) {
                    totalRatio = totalRatio.add(rule.getSplitValue());
                }
            }
            BigDecimal newTotal = totalRatio.add(value);
            if (newTotal.compareTo(MAX_BASIS_POINTS) > 0) {
                throw new IllegalArgumentException(
                        "Total RATIO splitValue exceeds 10000 basis points (current: "
                                + totalRatio + ", adding: " + value + ")");
            }
        } else {
            // FIXED 模式：value 必须大于 0
            if (value.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("FIXED splitValue must be > 0");
            }
        }

        SplitRule rule = new SplitRule();
        rule.setMerchantId(merchantId);
        rule.setSplitType(type);
        rule.setSplitValue(value);
        rule.setReceiverAddress(receiverAddress);
        rule.setDescription(description);
        rule.setActive(true);
        // 自动设置优先级：FIXED 先执行，RATIO 后执行
        rule.setPriority(type == SplitRule.SplitType.FIXED ? FIXED_PRIORITY : RATIO_PRIORITY);

        SplitRule saved = splitRuleRepository.save(rule);
        log.info("Created split rule: id={}, merchantId={}, type={}, value={}, receiver={}",
                saved.getId(), merchantId, type, value, receiverAddress);
        return saved;
    }

    /**
     * 获取商户的所有活跃分账规则。
     *
     * @param merchantId 商户 ID
     * @return 活跃分账规则列表（按 priority 升序）
     */
    public List<SplitRule> getSplitRules(Long merchantId) {
        return splitRuleRepository.findByMerchantIdAndActiveTrueOrderByPriorityAsc(merchantId);
    }

    /**
     * 停用分账规则（设置 active=false）。
     *
     * @param ruleId     规则 ID
     * @param merchantId 商户 ID（用于归属校验）
     * @throws IllegalArgumentException 如果规则不存在
     * @throws MerchantOwnershipException 如果规则不属于该商户
     */
    @Transactional
    public void deactivateSplitRule(Long ruleId, Long merchantId) {
        SplitRule rule = splitRuleRepository.findByIdAndMerchantId(ruleId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Split rule not found: " + ruleId));

        // 归属校验：findByIdAndMerchantId 已确保属于该商户，但额外防御
        ownershipGuard.requireOwned(merchantId, rule.getMerchantId(), "split rule", ruleId);

        rule.setActive(false);
        splitRuleRepository.save(rule);
        log.info("Deactivated split rule: id={}, merchantId={}", ruleId, merchantId);
    }

    // ==================== 分账计算 ====================

    /**
     * 计算分账明细（不持久化）。
     *
     * <p>核心分账计算逻辑：</p>
     * <ol>
     *   <li>获取商户所有活跃规则（按 priority 排序）</li>
     *   <li>FIXED 规则先执行：直接扣除固定金额</li>
     *   <li>RATIO 规则后执行：对剩余金额按比例分配</li>
     *   <li>校验：所有分账金额之和 ≤ totalAmount</li>
     *   <li>无规则时返回空列表</li>
     * </ol>
     *
     * @param orderNo     订单号
     * @param paymentId   支付 ID
     * @param merchantId  商户 ID
     * @param totalAmount 订单总金额
     * @return SplitOrder 列表（PENDING 状态，未持久化）
     */
    public List<SplitOrder> calculateSplits(String orderNo, Long paymentId, Long merchantId,
                                            BigDecimal totalAmount) {
        List<SplitRule> rules = splitRuleRepository
                .findByMerchantIdAndActiveTrueOrderByPriorityAsc(merchantId);

        if (rules.isEmpty()) {
            return List.of();
        }

        List<SplitOrder> splits = new ArrayList<>();

        // 第一阶段：FIXED 规则先执行，计算 FIXED 总扣除额
        BigDecimal fixedTotal = BigDecimal.ZERO;
        for (SplitRule rule : rules) {
            if (rule.getSplitType() == SplitRule.SplitType.FIXED) {
                // FIXED：直接扣除固定金额，但不能超过剩余金额
                BigDecimal splitAmount = rule.getSplitValue().min(totalAmount.subtract(fixedTotal));
                if (splitAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                SplitOrder order = createSplitOrder(orderNo, paymentId, merchantId, rule, splitAmount);
                splits.add(order);
                fixedTotal = fixedTotal.add(splitAmount);
            }
        }

        // 第二阶段：RATIO 规则对剩余金额（totalAmount - FIXED总额）独立按比例分配
        // 每条 RATIO 规则都从同一个剩余金额计算，不互相扣减
        BigDecimal remainingAfterFixed = totalAmount.subtract(fixedTotal);
        for (SplitRule rule : rules) {
            if (rule.getSplitType() == SplitRule.SplitType.RATIO) {
                // RATIO：splitAmount = remainingAfterFixed * (splitValue / 10000)
                BigDecimal splitAmount = remainingAfterFixed
                        .multiply(rule.getSplitValue())
                        .divide(MAX_BASIS_POINTS, 8, RoundingMode.HALF_UP);

                if (splitAmount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                SplitOrder order = createSplitOrder(orderNo, paymentId, merchantId, rule, splitAmount);
                splits.add(order);
            }
        }

        // 校验：所有分账金额之和 ≤ totalAmount
        BigDecimal totalSplit = BigDecimal.ZERO;
        for (SplitOrder split : splits) {
            totalSplit = totalSplit.add(split.getAmount());
        }
        if (totalSplit.compareTo(totalAmount) > 0) {
            log.error("Split total exceeds order amount: totalSplit={}, totalAmount={}",
                    totalSplit, totalAmount);
            throw new IllegalStateException("Split total exceeds order amount");
        }

        log.info("Calculated splits: orderNo={}, merchantId={}, splitCount={}, totalSplit={}",
                orderNo, merchantId, splits.size(), totalSplit);
        return splits;
    }

    /**
     * 执行分账：计算分账明细并持久化到数据库。
     *
     * @param orderNo     订单号
     * @param paymentId   支付 ID
     * @param merchantId  商户 ID
     * @param totalAmount 订单总金额
     * @return 已持久化的 SplitOrder 列表
     */
    @Transactional
    public List<SplitOrder> executeSplits(String orderNo, Long paymentId, Long merchantId,
                                          BigDecimal totalAmount) {
        List<SplitOrder> splits = calculateSplits(orderNo, paymentId, merchantId, totalAmount);

        if (splits.isEmpty()) {
            return List.of();
        }

        List<SplitOrder> saved = new ArrayList<>();
        for (SplitOrder split : splits) {
            saved.add(splitOrderRepository.save(split));
        }

        log.info("Executed splits: orderNo={}, merchantId={}, persistedCount={}",
                orderNo, merchantId, saved.size());
        return saved;
    }

    /**
     * 查询某订单的分账明细。
     *
     * @param orderId 订单号
     * @return 分账明细列表
     */
    public List<SplitOrder> getSplitOrders(String orderId) {
        return splitOrderRepository.findByOrderId(orderId);
    }

    /**
     * 计算商户最终可得金额。
     *
     * <p>商户可得金额 = totalAmount - 所有分账金额之和。
     * 如果没有分账规则，返回 totalAmount（全额归商户）。</p>
     *
     * @param totalAmount 订单总金额
     * @param splits      分账明细列表
     * @return 商户最终可得金额
     */
    public BigDecimal getMerchantSettlementAmount(BigDecimal totalAmount, List<SplitOrder> splits) {
        if (splits == null || splits.isEmpty()) {
            return totalAmount;
        }

        BigDecimal totalSplit = BigDecimal.ZERO;
        for (SplitOrder split : splits) {
            totalSplit = totalSplit.add(split.getAmount());
        }

        return totalAmount.subtract(totalSplit);
    }

    // ==================== 阶梯分账 ====================

    /**
     * 创建阶梯分账规则。
     *
     * <p>校验逻辑：</p>
     * <ul>
     *   <li>splitRatio 必须在 1-10000 范围内（基点）</li>
     *   <li>tierMinAmount 必须 >= 0</li>
     *   <li>tierMaxAmount（非 null 时）必须 > tierMinAmount</li>
     *   <li>同一商户的阶梯区间不能重叠</li>
     * </ul>
     *
     * @param merchantId      商户 ID
     * @param receiverAddress 接收地址
     * @param tierMinAmount   阶梯最小金额（含）
     * @param tierMaxAmount   阶梯最大金额（不含），null 表示无上限
     * @param splitRatio      分账比例（基点，1-10000）
     * @param description     规则描述（可选）
     * @return 创建的 TieredSplitRule
     * @throws IllegalArgumentException 如果参数校验失败
     */
    @Transactional
    public TieredSplitRule createTieredSplitRule(Long merchantId, String receiverAddress,
                                                  BigDecimal tierMinAmount, BigDecimal tierMaxAmount,
                                                  BigDecimal splitRatio, String description) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        if (receiverAddress == null || receiverAddress.isBlank()) {
            throw new IllegalArgumentException("receiverAddress is required");
        }
        if (tierMinAmount == null) {
            throw new IllegalArgumentException("tierMinAmount is required");
        }
        if (tierMinAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("tierMinAmount must be >= 0");
        }
        if (tierMaxAmount != null && tierMaxAmount.compareTo(tierMinAmount) <= 0) {
            throw new IllegalArgumentException("tierMaxAmount must be > tierMinAmount");
        }
        if (splitRatio == null) {
            throw new IllegalArgumentException("splitRatio is required");
        }
        if (splitRatio.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("splitRatio must be >= 1 (basis points)");
        }
        if (splitRatio.compareTo(MAX_BASIS_POINTS) > 0) {
            throw new IllegalArgumentException("splitRatio must be <= 10000 (basis points)");
        }

        // 校验区间不重叠
        List<TieredSplitRule> existingRules = tieredSplitRuleRepository
                .findByMerchantIdAndActiveTrueOrderByTierOrderAsc(merchantId);
        for (TieredSplitRule existing : existingRules) {
            if (intervalsOverlap(tierMinAmount, tierMaxAmount,
                    existing.getTierMinAmount(), existing.getTierMaxAmount())) {
                throw new IllegalArgumentException(
                        "Tier interval overlaps with existing rule: id=" + existing.getId()
                                + ", interval=[" + existing.getTierMinAmount() + ", "
                                + (existing.getTierMaxAmount() != null ? existing.getTierMaxAmount() : "∞") + ")");
            }
        }

        // 自动设置 tierOrder 为当前最大值 + 1
        int newTierOrder = existingRules.isEmpty() ? 0 :
                existingRules.get(existingRules.size() - 1).getTierOrder() + 1;

        TieredSplitRule rule = new TieredSplitRule();
        rule.setMerchantId(merchantId);
        rule.setReceiverAddress(receiverAddress);
        rule.setTierMinAmount(tierMinAmount);
        rule.setTierMaxAmount(tierMaxAmount);
        rule.setSplitRatio(splitRatio);
        rule.setDescription(description);
        rule.setActive(true);
        rule.setTierOrder(newTierOrder);

        TieredSplitRule saved = tieredSplitRuleRepository.save(rule);
        log.info("Created tiered split rule: id={}, merchantId={}, tier=[{}, {}), ratio={}",
                saved.getId(), merchantId, tierMinAmount,
                tierMaxAmount != null ? tierMaxAmount : "∞", splitRatio);
        return saved;
    }

    /**
     * 获取商户的所有活跃阶梯分账规则。
     *
     * @param merchantId 商户 ID
     * @return 活跃阶梯分账规则列表（按 tierOrder 升序）
     */
    public List<TieredSplitRule> getTieredSplitRules(Long merchantId) {
        return tieredSplitRuleRepository.findByMerchantIdAndActiveTrueOrderByTierOrderAsc(merchantId);
    }

    /**
     * 停用阶梯分账规则。
     *
     * @param ruleId     规则 ID
     * @param merchantId 商户 ID
     * @throws IllegalArgumentException 如果规则不存在
     */
    @Transactional
    public void deactivateTieredSplitRule(Long ruleId, Long merchantId) {
        TieredSplitRule rule = tieredSplitRuleRepository.findByIdAndMerchantId(ruleId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Tiered split rule not found: " + ruleId));

        ownershipGuard.requireOwned(merchantId, rule.getMerchantId(), "tiered split rule", ruleId);

        rule.setActive(false);
        tieredSplitRuleRepository.save(rule);
        log.info("Deactivated tiered split rule: id={}, merchantId={}", ruleId, merchantId);
    }

    /**
     * 计算阶梯分账明细（不持久化）。
     *
     * <p>阶梯分账逻辑：根据订单总金额匹配对应的阶梯区间，
     * 按该阶梯的分账比例计算分账金额。</p>
     *
     * @param orderNo     订单号
     * @param paymentId   支付 ID
     * @param merchantId  商户 ID
     * @param totalAmount 订单总金额
     * @return SplitOrder 列表（PENDING 状态，未持久化）
     */
    public List<SplitOrder> calculateTieredSplits(String orderNo, Long paymentId, Long merchantId,
                                                   BigDecimal totalAmount) {
        List<TieredSplitRule> rules = tieredSplitRuleRepository
                .findByMerchantIdAndActiveTrueOrderByTierOrderAsc(merchantId);

        if (rules.isEmpty()) {
            return List.of();
        }

        // 找到匹配的阶梯
        TieredSplitRule matchedRule = null;
        for (TieredSplitRule rule : rules) {
            if (rule.matchesTier(totalAmount)) {
                matchedRule = rule;
                break;
            }
        }

        if (matchedRule == null) {
            log.warn("No tiered split rule matched for amount: {}", totalAmount);
            return List.of();
        }

        // 按匹配阶梯的比例计算分账金额
        BigDecimal splitAmount = totalAmount
                .multiply(matchedRule.getSplitRatio())
                .divide(MAX_BASIS_POINTS, 8, RoundingMode.HALF_UP);

        if (splitAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }

        // 校验分账金额不超过订单金额
        if (splitAmount.compareTo(totalAmount) > 0) {
            log.error("Tiered split amount exceeds order amount: splitAmount={}, totalAmount={}",
                    splitAmount, totalAmount);
            throw new IllegalStateException("Tiered split amount exceeds order amount");
        }

        SplitOrder order = createTieredSplitOrder(orderNo, paymentId, merchantId, matchedRule, splitAmount);
        log.info("Calculated tiered split: orderNo={}, merchantId={}, tier=[{}, {}), ratio={}, splitAmount={}",
                orderNo, merchantId, matchedRule.getTierMinAmount(),
                matchedRule.getTierMaxAmount() != null ? matchedRule.getTierMaxAmount() : "∞",
                matchedRule.getSplitRatio(), splitAmount);
        return List.of(order);
    }

    /**
     * 执行阶梯分账：计算并持久化。
     *
     * @param orderNo     订单号
     * @param paymentId   支付 ID
     * @param merchantId  商户 ID
     * @param totalAmount 订单总金额
     * @return 已持久化的 SplitOrder 列表
     */
    @Transactional
    public List<SplitOrder> executeTieredSplits(String orderNo, Long paymentId, Long merchantId,
                                                 BigDecimal totalAmount) {
        List<SplitOrder> splits = calculateTieredSplits(orderNo, paymentId, merchantId, totalAmount);

        if (splits.isEmpty()) {
            return List.of();
        }

        List<SplitOrder> saved = new ArrayList<>();
        for (SplitOrder split : splits) {
            saved.add(splitOrderRepository.save(split));
        }

        log.info("Executed tiered splits: orderNo={}, merchantId={}, persistedCount={}",
                orderNo, merchantId, saved.size());
        return saved;
    }

    // --- Private helpers ---

    /**
     * 创建 SplitOrder 对象（PENDING 状态，未持久化）。
     */
    private SplitOrder createSplitOrder(String orderNo, Long paymentId, Long merchantId,
                                        SplitRule rule, BigDecimal splitAmount) {
        SplitOrder order = new SplitOrder();
        order.setOrderId(orderNo);
        order.setPaymentId(paymentId);
        order.setMerchantId(merchantId);
        order.setReceiverAddress(rule.getReceiverAddress());
        order.setAmount(splitAmount);
        order.setSplitType(rule.getSplitType());
        order.setSplitValue(rule.getSplitValue());
        order.setSplitRuleId(rule.getId());
        order.setDescription(rule.getDescription());
        order.setStatus(SplitOrder.SplitStatus.PENDING);
        return order;
    }

    /**
     * 创建阶梯分账的 SplitOrder 对象（PENDING 状态，未持久化）。
     */
    private SplitOrder createTieredSplitOrder(String orderNo, Long paymentId, Long merchantId,
                                               TieredSplitRule rule, BigDecimal splitAmount) {
        SplitOrder order = new SplitOrder();
        order.setOrderId(orderNo);
        order.setPaymentId(paymentId);
        order.setMerchantId(merchantId);
        order.setReceiverAddress(rule.getReceiverAddress());
        order.setAmount(splitAmount);
        order.setSplitType(SplitRule.SplitType.RATIO);
        order.setSplitValue(rule.getSplitRatio());
        order.setDescription(rule.getDescription());
        order.setStatus(SplitOrder.SplitStatus.PENDING);
        return order;
    }

    /**
     * 判断两个金额区间是否重叠。
     * 区间为左闭右开：[min1, max1) 和 [min2, max2)。
     * max 为 null 表示无上限。
     */
    private boolean intervalsOverlap(BigDecimal min1, BigDecimal max1,
                                      BigDecimal min2, BigDecimal max2) {
        // [min1, max1) 与 [min2, max2) 重叠的条件：min1 < max2 且 min2 < max1
        boolean min1BelowMax2 = max2 == null || min1.compareTo(max2) < 0;
        boolean min2BelowMax1 = max1 == null || min2.compareTo(max1) < 0;
        return min1BelowMax2 && min2BelowMax1;
    }
}