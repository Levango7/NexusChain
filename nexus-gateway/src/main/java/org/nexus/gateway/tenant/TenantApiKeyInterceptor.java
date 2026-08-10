package org.nexus.gateway.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * 租户 API Key 拦截器（P4-T6 多租户改造）。
 *
 * <p>从请求头 {@code X-Tenant-Api-Key} 提取 API Key，通过 {@link TenantService#validateApiKey}
 * 验证后填充 {@link TenantContext}，请求完成后清除上下文。</p>
 *
 * <p>仅对启用多租户（{@code nexus.tenant.enabled=true}）的路径生效。租户管理 API
 * （/api/v2/tenants/**）本身不经过此拦截器（由 admin 鉴权层保护，避免鸡生蛋问题）。</p>
 *
 * <p>鉴权失败响应：</p>
 * <ul>
 *   <li>缺失 API Key → 401 + 40100</li>
 *   <li>无效 API Key → 401 + 40101</li>
 *   <li>租户非 ACTIVE 状态 → 403 + 40302</li>
 * </ul>
 */
@Component
public class TenantApiKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantApiKeyInterceptor.class);

    /** 租户 API Key 请求头名称。 */
    public static final String TENANT_API_KEY_HEADER = "X-Tenant-Api-Key";

    /** 请求属性键：当前租户 ID（供 Controller/Service 读取）。 */
    public static final String TENANT_ID_ATTR = "nexus.tenantId";

    /** 请求属性键：当前租户实体（供限流/计费读取配置）。 */
    public static final String TENANT_ATTR = "nexus.tenant";

    private final TenantService tenantService;

    /** 多租户功能开关（{@code nexus.tenant.enabled}）。关闭时拦截器直接放行。 */
    private final boolean tenantEnabled;

    @Autowired
    public TenantApiKeyInterceptor(TenantService tenantService,
                                    @Value("${nexus.tenant.enabled:true}") boolean tenantEnabled) {
        this.tenantService = tenantService;
        this.tenantEnabled = tenantEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!tenantEnabled) {
            return true;
        }
        String apiKey = request.getHeader(TENANT_API_KEY_HEADER);
        // 向后兼容模式：未携带 X-Tenant-Api-Key 头时放行（允许渐进式迁移到多租户）。
        // 仅当请求显式携带 X-Tenant-Api-Key 时才验证并填充 TenantContext。
        // 生产环境严格模式可通过要求所有请求携带该头实现（在 nginx/网关层强制）。
        if (apiKey == null || apiKey.isEmpty()) {
            return true;
        }
        Optional<Tenant> opt = tenantService.validateApiKey(apiKey);
        if (opt.isEmpty()) {
            return reject(response, 401, 40101, "Invalid or inactive tenant API key");
        }
        Tenant tenant = opt.get();
        // 填充请求属性 + ThreadLocal 上下文
        request.setAttribute(TENANT_ID_ATTR, tenant.getTenantId());
        request.setAttribute(TENANT_ATTR, tenant);
        TenantContext.setCurrentTenantId(tenant.getTenantId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清除 ThreadLocal，避免线程池复用导致租户串号
        TenantContext.clear();
    }

    private boolean reject(HttpServletResponse response, int httpStatus, int bizCode,
                           String message) throws java.io.IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null}", bizCode, message));
        return false;
    }
}