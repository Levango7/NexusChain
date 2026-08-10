package org.nexus.gateway.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 租户 JPA Repository（P4-T6 多租户改造）。
 *
 * <p>提供按 tenantId、apiKey 查询租户的能力。{@link TenantApiKeyInterceptor}
 * 通过 {@link #findByApiKey} 在请求拦截阶段完成 API Key 验证。</p>
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    /** 按业务租户 ID 查询。 */
    Optional<Tenant> findByTenantId(String tenantId);

    /** 按 API Key 查询（拦截器鉴权用）。 */
    Optional<Tenant> findByApiKey(String apiKey);

    /** 判断 API Key 是否已存在（创建租户时唯一性校验）。 */
    boolean existsByApiKey(String apiKey);

    /** 判断业务租户 ID 是否已存在。 */
    boolean existsByTenantId(String tenantId);
}