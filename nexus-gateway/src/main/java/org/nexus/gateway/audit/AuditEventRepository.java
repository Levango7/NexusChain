package org.nexus.gateway.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByMerchantIdOrderByEventTimeDesc(Long merchantId);

    List<AuditEventEntity> findByActionOrderByEventTimeDesc(String action);

    List<AuditEventEntity> findByEventTimeBetween(Instant from, Instant to);
}
