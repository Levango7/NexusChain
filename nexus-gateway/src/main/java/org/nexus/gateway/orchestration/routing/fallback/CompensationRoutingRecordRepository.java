package org.nexus.gateway.orchestration.routing.fallback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 跨渠道补偿路由记录仓库（Wave 16 模块三）。
 */
public interface CompensationRoutingRecordRepository extends JpaRepository<CompensationRoutingRecord, Long> {

    /** 幂等检查：compensation_id 唯一。 */
    Optional<CompensationRoutingRecord> findByCompensationId(String compensationId);

    /** 按支付查询全部补偿路由记录（attempt_no 升序）。 */
    List<CompensationRoutingRecord> findByPaymentIdOrderByAttemptNoAsc(String paymentId);

    /** 按商户查询（创建时间降序）。 */
    List<CompensationRoutingRecord> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
}
