package org.nexus.gateway.webhook;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Webhook 订阅 Repository。
 *
 * <p>提供按商户 ID 和状态查询订阅的查询方法，供订阅管理服务和投递服务使用。</p>
 */
@Repository
public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {

    /** 查询商户的所有订阅（不含已删除）。 */
    List<WebhookSubscription> findByMerchantIdAndStatusNot(Long merchantId, WebhookSubscriptionStatus status);

    /** 查询商户的所有订阅（含已删除）。 */
    List<WebhookSubscription> findByMerchantId(Long merchantId);

    /** 查询商户的活跃订阅（供投递服务调用）。 */
    List<WebhookSubscription> findByMerchantIdAndStatus(Long merchantId, WebhookSubscriptionStatus status);

    /** 查询指定事件类型的活跃订阅（通过 eventTypes LIKE 模糊匹配）。 */
    List<WebhookSubscription> findByStatusAndEventTypesContaining(WebhookSubscriptionStatus status, String eventType);

    /** 按 ID 和商户 ID 查询（用于归属校验）。 */
    Optional<WebhookSubscription> findByIdAndMerchantId(Long id, Long merchantId);
}