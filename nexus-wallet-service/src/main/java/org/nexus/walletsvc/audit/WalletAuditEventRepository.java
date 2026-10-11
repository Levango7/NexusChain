package org.nexus.walletsvc.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WalletAuditEventRepository extends JpaRepository<WalletAuditEvent, Long> {

    List<WalletAuditEvent> findByRequestIdOrderByEventTimeDesc(String requestId);

    List<WalletAuditEvent> findByActionOrderByEventTimeDesc(String action);
}
