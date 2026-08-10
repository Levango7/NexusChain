package org.nexus.gateway.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订阅实体仓储（P4-T8 订阅与循环计费引擎）。
 *
 * <p>注意：bean 名称显式指定为 {@code subscriptionV2Repository}，避免与
 * {@code org.nexus.gateway.repository.SubscriptionRepository} 的默认 bean
 * 名称 {@code subscriptionRepository} 冲突。</p>
 */
@Repository("subscriptionV2Repository")
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    Optional<Subscription> findBySubscriptionId(String subscriptionId);

    List<Subscription> findByMerchantId(Long merchantId);

    /**
     * 查找所有处于指定状态且下次扣款时间早于 cutoff 的订阅（用于周期扣款调度）。
     */
    List<Subscription> findByStatusAndNextChargeAtBefore(SubscriptionStatus status, LocalDateTime cutoff);

    /**
     * 查找处于多个状态之一且下次扣款时间早于 cutoff 的订阅。
     * 用于调度器同时扫描 ACTIVE 与 PAST_DUE 状态的到期订阅。
     */
    List<Subscription> findByStatusInAndNextChargeAtBefore(List<SubscriptionStatus> statuses, LocalDateTime cutoff);
}