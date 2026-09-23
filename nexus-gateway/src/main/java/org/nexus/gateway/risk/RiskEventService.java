package org.nexus.gateway.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 风控事件核心服务。
 *
 * <p>负责风控事件的创建、持久化和查询。所有便捷方法都委托给 {@link #recordEvent}
 * 核心方法，避免重复代码。</p>
 *
 * <h3>事件类型与便捷方法对应</h3>
 * <ul>
 *   <li>{@link RiskEvent.EventType#PAYMENT_EVALUATION} ← {@link #recordPaymentEvaluation}</li>
 *   <li>{@link RiskEvent.EventType#REFUND_EVALUATION} ← {@link #recordRefundEvaluation}</li>
 *   <li>{@link RiskEvent.EventType#RULE_TRIGGERED} ← {@link #recordRuleTriggered}</li>
 *   <li>{@link RiskEvent.EventType#MANUAL_REVIEW} ← {@link #recordManualReview}</li>
 *   <li>{@link RiskEvent.EventType#BLACKLIST_ACTION} ← {@link #recordBlacklistAction}</li>
 * </ul>
 */
@Service
public class RiskEventService {

    private static final Logger log = LoggerFactory.getLogger(RiskEventService.class);

    private final RiskEventRepository riskEventRepository;

    public RiskEventService(RiskEventRepository riskEventRepository) {
        this.riskEventRepository = riskEventRepository;
    }

    // ==================== 核心方法 ====================

    /**
     * 创建并持久化风控事件（核心方法）。
     *
     * <p>所有便捷方法都委托给此方法。eventId 自动生成（UUID 去横线格式），
     * occurredAt 自动设置为当前时间。</p>
     *
     * @param eventType      事件类型
     * @param merchantId     商户 ID（可空，系统级事件为 null）
     * @param orderId        关联订单号（可空）
     * @param payerAddress   付款方地址（可空）
     * @param payeeAddress   收款方地址（可空）
     * @param amount         交易金额（可空）
     * @param currency       币种（可空）
     * @param riskDecision   风控决策（可空，APPROVED/REJECTED/PENDING_REVIEW/FROZEN）
     * @param riskScore      风控评分（可空，0-100）
     * @param triggeredRules 触发的规则列表（可空，逗号分隔）
     * @param description    事件描述（可空）
     * @param fingerprintHash 设备指纹哈希（可空）
     * @param ipAddress      IP 地址（可空）
     * @return 已持久化的 RiskEvent
     */
    @Transactional
    public RiskEvent recordEvent(RiskEvent.EventType eventType, Long merchantId, String orderId,
                                  String payerAddress, String payeeAddress, BigDecimal amount,
                                  String currency, String riskDecision, Integer riskScore,
                                  String triggeredRules, String description,
                                  String fingerprintHash, String ipAddress) {
        RiskEvent event = new RiskEvent();
        event.setEventId(UUID.randomUUID().toString().replace("-", ""));
        event.setEventType(eventType);
        event.setMerchantId(merchantId);
        event.setOrderId(orderId);
        event.setPayerAddress(payerAddress);
        event.setPayeeAddress(payeeAddress);
        event.setAmount(amount);
        event.setCurrency(currency);
        event.setRiskDecision(riskDecision);
        event.setRiskScore(riskScore);
        event.setTriggeredRules(triggeredRules);
        event.setDescription(description);
        event.setFingerprintHash(fingerprintHash);
        event.setIpAddress(ipAddress);
        event.setOccurredAt(LocalDateTime.now());

        RiskEvent saved = riskEventRepository.save(event);
        log.info("Recorded risk event: eventId={}, type={}, merchantId={}, decision={}",
                saved.getEventId(), eventType, merchantId, riskDecision);
        return saved;
    }

    // ==================== 便捷方法 ====================

    /**
     * 记录支付风控评估事件。
     *
     * @param merchantId     商户 ID
     * @param orderId        关联订单号
     * @param payerAddress   付款方地址
     * @param amount         交易金额
     * @param currency       币种
     * @param riskDecision   风控决策
     * @param riskScore      风控评分
     * @param triggeredRules 触发的规则列表
     * @return 已持久化的 RiskEvent
     */
    @Transactional
    public RiskEvent recordPaymentEvaluation(Long merchantId, String orderId, String payerAddress,
                                              BigDecimal amount, String currency, String riskDecision,
                                              Integer riskScore, String triggeredRules) {
        return recordEvent(RiskEvent.EventType.PAYMENT_EVALUATION, merchantId, orderId,
                payerAddress, null, amount, currency, riskDecision, riskScore,
                triggeredRules, null, null, null);
    }

    /**
     * 记录退款风控评估事件。
     *
     * @param merchantId      商户 ID
     * @param orderId         关联订单号
     * @param receiverAddress 收款方地址（退款接收方）
     * @param amount          退款金额
     * @param riskDecision    风控决策
     * @param riskScore       风控评分
     * @param triggeredRules  触发的规则列表
     * @return 已持久化的 RiskEvent
     */
    @Transactional
    public RiskEvent recordRefundEvaluation(Long merchantId, String orderId, String receiverAddress,
                                             BigDecimal amount, String riskDecision,
                                             Integer riskScore, String triggeredRules) {
        return recordEvent(RiskEvent.EventType.REFUND_EVALUATION, merchantId, orderId,
                null, receiverAddress, amount, null, riskDecision, riskScore,
                triggeredRules, null, null, null);
    }

    /**
     * 记录风控规则触发事件。
     *
     * @param merchantId  商户 ID
     * @param orderId     关联订单号
     * @param ruleId      触发的规则 ID
     * @param description 事件描述
     * @return 已持久化的 RiskEvent
     */
    @Transactional
    public RiskEvent recordRuleTriggered(Long merchantId, String orderId, String ruleId,
                                          String description) {
        return recordEvent(RiskEvent.EventType.RULE_TRIGGERED, merchantId, orderId,
                null, null, null, null, null, null,
                ruleId, description, null, null);
    }

    /**
     * 记录人工复核事件。
     *
     * @param merchantId  商户 ID
     * @param orderId     关联订单号
     * @param description 事件描述
     * @return 已持久化的 RiskEvent
     */
    @Transactional
    public RiskEvent recordManualReview(Long merchantId, String orderId, String description) {
        return recordEvent(RiskEvent.EventType.MANUAL_REVIEW, merchantId, orderId,
                null, null, null, null, null, null,
                null, description, null, null);
    }

    /**
     * 记录黑名单操作事件。
     *
     * @param merchantId    商户 ID
     * @param targetAddress 目标地址
     * @param action        操作类型（如 ADD/REMOVE）
     * @param reason        操作原因
     * @return 已持久化的 RiskEvent
     */
    @Transactional
    public RiskEvent recordBlacklistAction(Long merchantId, String targetAddress, String action,
                                            String reason) {
        return recordEvent(RiskEvent.EventType.BLACKLIST_ACTION, merchantId, null,
                null, targetAddress, null, null, null, null,
                null, action + ": " + reason, null, null);
    }

    // ==================== 查询方法 ====================

    /**
     * 获取商户的风控事件列表（按时间倒序）。
     *
     * @param merchantId 商户 ID
     * @return 风控事件列表
     */
    public List<RiskEvent> getEventsByMerchant(Long merchantId) {
        return riskEventRepository.findByMerchantIdOrderByOccurredAtDesc(merchantId);
    }

    /**
     * 按事件类型查询风控事件。
     *
     * @param eventType 事件类型
     * @return 风控事件列表
     */
    public List<RiskEvent> getEventsByType(RiskEvent.EventType eventType) {
        return riskEventRepository.findByEventTypeOrderByOccurredAtDesc(eventType);
    }

    /**
     * 按风控决策查询风控事件（如所有 REJECTED 事件）。
     *
     * @param riskDecision 风控决策
     * @return 风控事件列表
     */
    public List<RiskEvent> getEventsByDecision(String riskDecision) {
        return riskEventRepository.findByRiskDecisionOrderByOccurredAtDesc(riskDecision);
    }

    /**
     * 获取某订单的所有风控事件。
     *
     * @param orderId 订单号
     * @return 风控事件列表
     */
    public List<RiskEvent> getEventsByOrder(String orderId) {
        return riskEventRepository.findByOrderId(orderId);
    }

    /**
     * 按时间范围查询风控事件。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 风控事件列表
     */
    public List<RiskEvent> getEventsByTimeRange(LocalDateTime start, LocalDateTime end) {
        return riskEventRepository.findByOccurredAtBetweenOrderByOccurredAtDesc(start, end);
    }

    /**
     * 统计商户某风控决策的事件数（用于仪表盘）。
     *
     * @param merchantId   商户 ID
     * @param riskDecision 风控决策
     * @return 事件数
     */
    public long countEventsByMerchantAndDecision(Long merchantId, String riskDecision) {
        return riskEventRepository.countByMerchantIdAndRiskDecision(merchantId, riskDecision);
    }

    /**
     * 统计某事件类型的事件数。
     *
     * @param eventType 事件类型
     * @return 事件数
     */
    public long countEventsByType(RiskEvent.EventType eventType) {
        return riskEventRepository.countByEventType(eventType);
    }
}