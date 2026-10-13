package org.nexus.gateway.subscription.claim;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 订阅周期扣款幂等认领仓储。
 *
 * <p>唯一键 {@code (subscription_id, cycle_key)} 由 {@link SubscriptionChargeClaim}
 * 实体与迁移 V98 共同声明；本仓储只做落库，裁决交给数据库唯一约束。</p>
 */
@Repository
public interface SubscriptionChargeClaimRepository extends JpaRepository<SubscriptionChargeClaim, Long> {

    long countBySubscriptionIdAndCycleKey(String subscriptionId, String cycleKey);
}
