package org.nexus.gateway.orchestration.repository;

import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrchestratedPaymentRepository extends JpaRepository<OrchestratedPayment, String> {
    Page<OrchestratedPayment> findByMerchantId(Long merchantId, Pageable pageable);
    Page<OrchestratedPayment> findByMerchantIdAndStatus(Long merchantId, OrchPaymentStatus status, Pageable pageable);
    List<OrchestratedPayment> findByStatus(OrchPaymentStatus status);
    long countByMerchantIdAndStatus(Long merchantId, OrchPaymentStatus status);
}