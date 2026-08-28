package org.nexus.gateway.repository;

import org.nexus.gateway.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByMerchantId(Long merchantId);

    List<Subscription> findByStatusAndNextChargeAtBefore(Subscription.SubscriptionStatus status, LocalDateTime cutoff);

    /**
     * 原子认领一次扣款周期（P0-4 审计修复）。
     *
     * <p>条件 UPDATE（status=ACTIVE 且 nextChargeAt&lt;=now）由数据库行级原子性保证：
     * 并发调用 / 多实例部署下，同一订阅的同一周期<b>有且仅有一个</b>认领成功
     * （认领即推进 chargedCount 与 nextChargeAt）。替代原"先转账后落库 +
     * 实体无乐观锁"的双重扣款路径。</p>
     *
     * @param id           订阅 ID
     * @param active       ACTIVE 状态枚举（参数化避免 JPQL 枚举字面量）
     * @param now          当前时间（到期判定）
     * @param nextChargeAt 认领成功后写入的下次扣款时间
     * @return 1 = 认领成功；0 = 未 ACTIVE / 未到期 / 已被并发认领
     */
    @Modifying
    @Query("UPDATE Subscription s SET s.chargedCount = s.chargedCount + 1, s.nextChargeAt = :nextChargeAt "
            + "WHERE s.id = :id AND s.status = :active AND s.nextChargeAt <= :now")
    int claimCharge(@Param("id") Long id,
                    @Param("active") Subscription.SubscriptionStatus active,
                    @Param("now") LocalDateTime now,
                    @Param("nextChargeAt") LocalDateTime nextChargeAt);
}
