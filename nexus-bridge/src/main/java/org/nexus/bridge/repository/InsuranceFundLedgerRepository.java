package org.nexus.bridge.repository;

import org.nexus.bridge.model.InsuranceFundLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 保险基金流水 Repository。
 *
 * @since 1.2
 */
@Repository
public interface InsuranceFundLedgerRepository extends JpaRepository<InsuranceFundLedgerEntry, Long> {
}