package org.nexus.gateway.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 风控事件 Repository。
 *
 * <p>提供按商户、类型、决策、订单、时间范围查询风控事件等方法。</p>
 */
@Repository
public interface RiskEventRepository extends JpaRepository<RiskEvent, Long> {

    /**
     * 按商户 ID 查询风控事件（按发生时间倒序）。
     *
     * @param merchantId 商户 ID
     * @return 风控事件列表
     */
    List<RiskEvent> findByMerchantIdOrderByOccurredAtDesc(Long merchantId);

    /**
     * 按事件类型查询风控事件（按发生时间倒序）。
     *
     * @param eventType 事件类型
     * @return 风控事件列表
     */
    List<RiskEvent> findByEventTypeOrderByOccurredAtDesc(RiskEvent.EventType eventType);

    /**
     * 按风控决策查询风控事件（按发生时间倒序）。
     *
     * @param riskDecision 风控决策（APPROVED/REJECTED/PENDING_REVIEW/FROZEN）
     * @return 风控事件列表
     */
    List<RiskEvent> findByRiskDecisionOrderByOccurredAtDesc(String riskDecision);

    /**
     * 按订单号查询风控事件。
     *
     * @param orderId 订单号
     * @return 风控事件列表
     */
    List<RiskEvent> findByOrderId(String orderId);

    /**
     * 按时间范围查询风控事件（按发生时间倒序）。
     *
     * @param start 开始时间
     * @param end   结束时间
     * @return 风控事件列表
     */
    List<RiskEvent> findByOccurredAtBetweenOrderByOccurredAtDesc(LocalDateTime start, LocalDateTime end);

    /**
     * 统计商户某风控决策的事件数。
     *
     * @param merchantId   商户 ID
     * @param riskDecision 风控决策
     * @return 事件数
     */
    long countByMerchantIdAndRiskDecision(Long merchantId, String riskDecision);

    /**
     * 统计某事件类型的事件数。
     *
     * @param eventType 事件类型
     * @return 事件数
     */
    long countByEventType(RiskEvent.EventType eventType);
}