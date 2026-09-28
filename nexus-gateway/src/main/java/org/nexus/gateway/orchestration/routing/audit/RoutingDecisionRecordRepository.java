package org.nexus.gateway.orchestration.routing.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 路由决策审计记录仓库（Wave 16 模块五）。
 */
public interface RoutingDecisionRecordRepository extends JpaRepository<RoutingDecisionRecord, String> {

    /** 按支付查询（创建时间降序）。 */
    List<RoutingDecisionRecord> findByPaymentIdOrderByCreatedAtDesc(String paymentId);

    /** 按商户查询（创建时间降序，IDOR 防护：只允许查自身，由服务层约束）。 */
    List<RoutingDecisionRecord> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /** 按实验查询（实验效果分析）。 */
    List<RoutingDecisionRecord> findByExperimentId(String experimentId);

    /** 保留期清理候选。 */
    List<RoutingDecisionRecord> findByCreatedAtBefore(LocalDateTime cutoff);
}
