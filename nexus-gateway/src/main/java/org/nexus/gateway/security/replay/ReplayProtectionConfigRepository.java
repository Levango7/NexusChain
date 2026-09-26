package org.nexus.gateway.security.replay;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 防重放保护配置 JPA 仓储。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.2。</p>
 */
@Repository
public interface ReplayProtectionConfigRepository extends JpaRepository<ReplayProtectionConfig, Long> {

    /**
     * 按租户 ID 查询防重放配置。
     *
     * @param tenantId 租户 ID
     * @return 配置实体（每个租户最多一条）
     */
    Optional<ReplayProtectionConfig> findByTenantId(String tenantId);
}