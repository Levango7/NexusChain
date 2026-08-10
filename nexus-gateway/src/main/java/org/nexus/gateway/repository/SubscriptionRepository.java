package org.nexus.gateway.repository;

import org.nexus.gateway.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByMerchantId(Long merchantId);

    List<Subscription> findByStatusAndNextChargeAtBefore(Subscription.SubscriptionStatus status, LocalDateTime cutoff);
}
