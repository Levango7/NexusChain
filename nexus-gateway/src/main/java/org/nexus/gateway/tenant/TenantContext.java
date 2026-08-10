package org.nexus.gateway.tenant;

/**
 * 租户上下文（P4-T6 多租户改造）。
 *
 * <p>使用 {@link ThreadLocal} 在请求处理线程内传递当前租户 ID，用于数据隔离层
 * （Repository 查询条件、新实体填充 tenant_id）和租户级限流/计费。</p>
 *
 * <p>生命周期：{@link TenantApiKeyInterceptor} 在 preHandle 阶段从请求头解析出
 * tenantId 后调用 {@link #setCurrentTenantId}；请求处理完成后（afterCompletion）
 * 必须调用 {@link #clear} 以避免线程池复用导致的租户串号。</p>
 *
 * <p>注意：在异步线程（@Async / @Scheduled / Kafka listener）中执行租户相关操作时，
 * 必须显式传播上下文（如 TaskDecorator 包装），否则 {@link #getCurrentTenantId}
 * 返回 {@code null}。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 设置当前线程的租户 ID。
     *
     * @param tenantId 租户 ID（业务 UUID）
     */
    public static void setCurrentTenantId(String tenantId) {
        CURRENT_TENANT_ID.set(tenantId);
    }

    /**
     * 获取当前线程的租户 ID。
     *
     * @return 租户 ID；若上下文未设置则返回 {@code null}
     */
    public static String getCurrentTenantId() {
        return CURRENT_TENANT_ID.get();
    }

    /**
     * 获取当前线程的租户 ID，若未设置则抛出异常。
     *
     * @return 租户 ID
     * @throws IllegalStateException 当前线程未设置租户上下文
     */
    public static String requireCurrentTenantId() {
        String tenantId = CURRENT_TENANT_ID.get();
        if (tenantId == null) {
            throw new IllegalStateException("Tenant context not set on current thread");
        }
        return tenantId;
    }

    /**
     * 判断当前线程是否已设置租户上下文。
     *
     * @return {@code true} 若已设置
     */
    public static boolean isPresent() {
        return CURRENT_TENANT_ID.get() != null;
    }

    /**
     * 清除当前线程的租户上下文。
     *
     * <p>必须在请求处理完成后调用（拦截器 afterCompletion 或 try-finally），
     * 防止线程池复用时租户上下文串号。</p>
     */
    public static void clear() {
        CURRENT_TENANT_ID.remove();
    }
}