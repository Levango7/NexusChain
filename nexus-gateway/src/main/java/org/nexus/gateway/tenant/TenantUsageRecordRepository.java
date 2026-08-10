package org.nexus.gateway.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 租户使用量记录 Repository（P4-T6 多租户改造）。
 */
@Repository
public interface TenantUsageRecordRepository extends JpaRepository<TenantUsageRecord, Long> {

    /** 按租户 + 周期查询（计费聚合用）。 */
    Optional<TenantUsageRecord> findByTenantIdAndPeriod(String tenantId, String period);
}