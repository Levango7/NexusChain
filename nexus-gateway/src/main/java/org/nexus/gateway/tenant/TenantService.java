package org.nexus.gateway.tenant;

import java.util.Optional;

/**
 * 租户管理服务接口（P4-T6 多租户改造）。
 *
 * <p>提供租户 CRUD、状态流转（暂停/终止/恢复）和 API Key 验证能力。
 * {@link TenantApiKeyInterceptor} 通过 {@link #validateApiKey} 在请求拦截阶段
 * 完成鉴权并填充 {@link TenantContext}。</p>
 */
public interface TenantService {

    /**
     * 创建租户。
     *
     * @param tenant 待创建租户（tenantId/apiKey 可空，由服务层生成）
     * @return 已持久化的租户
     */
    Tenant createTenant(Tenant tenant);

    /**
     * 按业务租户 ID 查询租户。
     *
     * @param tenantId 业务租户 ID
     * @return 租户实体
     */
    Optional<Tenant> getTenant(String tenantId);

    /**
     * 更新租户基本信息与配置。
     *
     * @param tenantId 业务租户 ID
     * @param updated  待更新字段（name、config 等）
     * @return 更新后的租户
     */
    Tenant updateTenant(String tenantId, Tenant updated);

    /**
     * 更新租户配置（限流配额、费率等）。
     *
     * @param tenantId 业务租户 ID
     * @param config   新配置
     * @return 更新后的租户
     */
    Tenant updateTenantConfig(String tenantId, TenantConfig config);

    /**
     * 暂停租户（{@link TenantStatus#ACTIVE} → {@link TenantStatus#SUSPENDED}）。
     *
     * @param tenantId 业务租户 ID
     * @return 更新后的租户
     */
    Tenant suspendTenant(String tenantId);

    /**
     * 终止租户（任意状态 → {@link TenantStatus#TERMINATED}，终态不可恢复）。
     *
     * @param tenantId 业务租户 ID
     * @return 更新后的租户
     */
    Tenant terminateTenant(String tenantId);

    /**
     * 恢复暂停的租户（{@link TenantStatus#SUSPENDED} → {@link TenantStatus#ACTIVE}）。
     *
     * @param tenantId 业务租户 ID
     * @return 更新后的租户
     */
    Tenant activateTenant(String tenantId);

    /**
     * 验证 API Key 并返回对应租户。
     *
     * <p>仅 {@link TenantStatus#ACTIVE} 状态的租户通过验证；
     * {@link TenantStatus#SUSPENDED} / {@link TenantStatus#TERMINATED} 拒绝。</p>
     *
     * @param apiKey 请求头携带的 API Key
     * @return 验证通过的租户；不存在或非 ACTIVE 状态返回 empty
     */
    Optional<Tenant> validateApiKey(String apiKey);
}