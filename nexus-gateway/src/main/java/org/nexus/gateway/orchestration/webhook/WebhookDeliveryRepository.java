package org.nexus.gateway.orchestration.webhook;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Webhook 投递记录 Repository（P4-T5）。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Repository
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryRecord, String> {

    /** 按支付 ID 查询投递记录（一个支付可能有多次状态变更投递）。 */
    List<WebhookDeliveryRecord> findByPaymentId(String paymentId);

    /** 按支付 ID 查询最新一条投递记录。 */
    Optional<WebhookDeliveryRecord> findFirstByPaymentIdOrderByCreatedAtDesc(String paymentId);

    /** 按商户 ID 分页查询投递记录。 */
    Page<WebhookDeliveryRecord> findByMerchantId(Long merchantId, Pageable pageable);

    /** 按商户 ID + 状态分页查询。 */
    Page<WebhookDeliveryRecord> findByMerchantIdAndStatus(Long merchantId, WebhookDeliveryStatus status, Pageable pageable);

    /** 按状态查询（用于监控/统计）。 */
    List<WebhookDeliveryRecord> findByStatus(WebhookDeliveryStatus status);

    /** 按状态分页查询。 */
    Page<WebhookDeliveryRecord> findByStatus(WebhookDeliveryStatus status, Pageable pageable);

    /** 按支付 ID + 状态查询（去重：同一支付同一状态只投递一次）。 */
    Optional<WebhookDeliveryRecord> findByPaymentIdAndStatus(String paymentId, String status);
}