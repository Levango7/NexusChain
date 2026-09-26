package org.nexus.gateway.security.threeds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 3DS 配置 JPA 仓储。
 *
 * <p>支持按租户+商户查询（商户级优先）和按租户查询（租户级回退）。</p>
 */
@Repository
public interface ThreeDsConfigRepository extends JpaRepository<ThreeDsConfig, Long> {

    /** 按租户 + 商户查询配置（商户级配置）。 */
    Optional<ThreeDsConfig> findByTenantIdAndMerchantId(String tenantId, Long merchantId);

    /** 按租户查询配置（merchantId 为 NULL 的租户级配置）。 */
    Optional<ThreeDsConfig> findByTenantIdAndMerchantIdIsNull(String tenantId);
}