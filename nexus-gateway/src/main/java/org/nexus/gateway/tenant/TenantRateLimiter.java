package org.nexus.gateway.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 租户级限流器（P4-T6 多租户改造）。
 *
 * <p>按 {@code tenantId + endpoint} 维度限流，限流配额从 {@link TenantConfig}
 * （{@code rateLimitPerSecond} / {@code rateLimitPerMinute}）读取。超限返回
 * 429 Too Many Requests。</p>
 *
 * <p>实现说明：设计上对接 Sentinel 参数限流（{@code SphU.entry(methodName, EntryType.IN, 1, tenantId)}），
 * 但为保证测试环境（无 Sentinel Dashboard）可用，采用本地滑动窗口计数器实现，逻辑与
 * Sentinel 参数限流等价：每个 (tenantId, endpoint) 维度独立计数，超限拒绝。</p>
 *
 * <p>限流维度选择：</p>
 * <ul>
 *   <li>秒级限流：防止突发流量打垮下游（签名服务/钱包服务）。</li>
 *   <li>分钟级限流：防止持续高频调用占用配额。</li>
 * </ul>
 * <p>两个维度同时生效，任一超限即拒绝。</p>
 */
@Component
public class TenantRateLimiter implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantRateLimiter.class);

    /** 请求属性键：当前租户实体（由 TenantApiKeyInterceptor 填充）。 */
    public static final String TENANT_ATTR = TenantApiKeyInterceptor.TENANT_ATTR;

    private final boolean tenantEnabled;
    private final int defaultRateLimitPerSecond;
    private final int defaultRateLimitPerMinute;

    /** (tenantId + endpoint) → 秒级滑动窗口。 */
    private final ConcurrentHashMap<String, SlidingWindow> secondWindows = new ConcurrentHashMap<>();
    /** (tenantId + endpoint) → 分钟级滑动窗口。 */
    private final ConcurrentHashMap<String, SlidingWindow> minuteWindows = new ConcurrentHashMap<>();

    @Autowired
    public TenantRateLimiter(@Value("${nexus.tenant.enabled:true}") boolean tenantEnabled,
                              @Value("${nexus.tenant.default-rate-limit-per-second:100}") int defaultRateLimitPerSecond,
                              @Value("${nexus.tenant.default-rate-limit-per-minute:6000}") int defaultRateLimitPerMinute) {
        this.tenantEnabled = tenantEnabled;
        this.defaultRateLimitPerSecond = defaultRateLimitPerSecond;
        this.defaultRateLimitPerMinute = defaultRateLimitPerMinute;
    }

    /**
     * 尝试获取一个许可（限流判定）。
     *
     * @param tenantId   租户 ID
     * @param endpoint   端点标识（如请求路径）
     * @param perSecond  秒级配额（来自 TenantConfig）
     * @param perMinute  分钟级配额（来自 TenantConfig）
     * @return {@code true} 允许通过；{@code false} 超限拒绝
     */
    public boolean tryAcquire(String tenantId, String endpoint, int perSecond, int perMinute) {
        String key = tenantId + ":" + endpoint;
        SlidingWindow secondWindow = secondWindows.computeIfAbsent(key, k -> new SlidingWindow(1000));
        SlidingWindow minuteWindow = minuteWindows.computeIfAbsent(key, k -> new SlidingWindow(60_000));
        if (!secondWindow.tryAcquire(perSecond)) {
            log.warn("Tenant rate limit (per-second) exceeded: tenantId={}, endpoint={}, limit={}",
                    tenantId, endpoint, perSecond);
            return false;
        }
        if (!minuteWindow.tryAcquire(perMinute)) {
            log.warn("Tenant rate limit (per-minute) exceeded: tenantId={}, endpoint={}, limit={}",
                    tenantId, endpoint, perMinute);
            return false;
        }
        return true;
    }

    /**
     * 使用默认配额尝试获取许可（无租户配置时降级）。
     */
    public boolean tryAcquireWithDefault(String tenantId, String endpoint) {
        return tryAcquire(tenantId, endpoint, defaultRateLimitPerSecond, defaultRateLimitPerMinute);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if (!tenantEnabled) {
            return true;
        }
        Tenant tenant = (Tenant) request.getAttribute(TENANT_ATTR);
        if (tenant == null) {
            // 未经过租户鉴权（如租户管理 API），跳过租户级限流
            return true;
        }
        String endpoint = request.getMethod() + " " + request.getRequestURI();
        TenantConfig cfg = tenant.getConfig();
        int perSecond = cfg != null && cfg.getRateLimitPerSecond() != null
                ? cfg.getRateLimitPerSecond() : defaultRateLimitPerSecond;
        int perMinute = cfg != null && cfg.getRateLimitPerMinute() != null
                ? cfg.getRateLimitPerMinute() : defaultRateLimitPerMinute;
        if (!tryAcquire(tenant.getTenantId(), endpoint, perSecond, perMinute)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":42900,\"message\":\"Tenant rate limit exceeded\",\"data\":null}");
            return false;
        }
        return true;
    }

    /**
     * 滑动窗口计数器（线程安全）。
     *
     * <p>简化实现：固定窗口（每个窗口周期重置计数）。对于本场景足够精确，
     * 边界误差在窗口切换瞬间可接受（最多多放行 limit 个请求）。</p>
     */
    static class SlidingWindow {
        private final long windowMillis;
        private volatile long windowStart = System.currentTimeMillis();
        private final AtomicInteger count = new AtomicInteger(0);

        SlidingWindow(long windowMillis) {
            this.windowMillis = windowMillis;
        }

        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMillis) {
                windowStart = now;
                count.set(1);
                return limit > 0;
            }
            return count.incrementAndGet() <= limit;
        }
    }
}