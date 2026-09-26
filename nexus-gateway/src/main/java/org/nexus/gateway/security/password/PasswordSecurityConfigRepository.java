package org.nexus.gateway.security.password;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 密码安全策略配置 JPA 仓储。
 *
 * <p>按 tenantId 查询密码安全策略配置。未配置的租户使用默认值。</p>
 */
@Repository
public interface PasswordSecurityConfigRepository extends JpaRepository<PasswordSecurityConfig, Long> {

    /**
     * 按租户 ID 查找密码安全策略配置。
     *
     * @param tenantId 租户 ID
     * @return 配置记录
     */
    Optional<PasswordSecurityConfig> findByTenantId(String tenantId);
}