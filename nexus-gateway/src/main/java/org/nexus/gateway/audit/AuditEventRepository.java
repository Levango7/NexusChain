package org.nexus.gateway.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 审计事件仓储（只读语义：实体 append-only，本接口不暴露改/删用法）。
 *
 * <p><b>2026-10-26 修复</b>：原 {@code findByActionOrderByEventTimeDesc} /
 * {@code findByEventTimeBetween} 是<b>全平台</b>查询，却被控制器直接用于商户可见端点，
 * 导致任何商户都能读到所有商户的审计事件。现改为三个「按商户」变体，
 * 全量查询不再提供（跨商户视图需要 ADMIN 身份，属独立配置变更）。</p>
 */
@Repository
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findByMerchantIdOrderByEventTimeDesc(Long merchantId);

    List<AuditEventEntity> findByMerchantIdAndActionOrderByEventTimeDesc(
            Long merchantId, String action);

    List<AuditEventEntity> findByMerchantIdAndEventTimeBetweenOrderByEventTimeDesc(
            Long merchantId, Instant from, Instant to);

    Page<AuditEventEntity> findByMerchantId(Long merchantId, Pageable pageable);
}
