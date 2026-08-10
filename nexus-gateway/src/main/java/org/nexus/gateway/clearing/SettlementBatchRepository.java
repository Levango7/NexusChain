package org.nexus.gateway.clearing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for settlement batches.
 */
@Repository
public interface SettlementBatchRepository extends JpaRepository<SettlementBatch, Long> {

    List<SettlementBatch> findByMerchantId(Long merchantId);

    List<SettlementBatch> findByMerchantIdAndPeriod(Long merchantId, SettlementPeriod period);
}
