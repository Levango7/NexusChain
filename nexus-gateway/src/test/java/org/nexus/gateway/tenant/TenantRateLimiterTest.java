package org.nexus.gateway.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TenantRateLimiter} 单元测试（P4-T6 多租户改造）。
 *
 * <p>覆盖按租户隔离限流、秒级/分钟级超限拒绝、不同租户独立计数。</p>
 */
class TenantRateLimiterTest {

    private TenantRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // 默认配额：100/秒，6000/分钟
        rateLimiter = new TenantRateLimiter(true, 100, 6000);
    }

    @Test
    @DisplayName("未超限：连续请求应放行")
    void underLimitPasses() {
        for (int i = 0; i < 50; i++) {
            assertTrue(rateLimiter.tryAcquire("t-a", "/api/v1/orders", 100, 6000),
                    "Request " + i + " should pass");
        }
    }

    @Test
    @DisplayName("秒级超限：超过 perSecond 配额后拒绝")
    void perSecondLimitExceeded() {
        int perSecond = 5;
        // 前 5 个放行
        for (int i = 0; i < perSecond; i++) {
            assertTrue(rateLimiter.tryAcquire("t-a", "/api/v1/orders", perSecond, 6000),
                    "Request " + i + " should pass");
        }
        // 第 6 个拒绝
        assertFalse(rateLimiter.tryAcquire("t-a", "/api/v1/orders", perSecond, 6000),
                "Request 6 should be rejected");
    }

    @Test
    @DisplayName("不同租户独立计数：租户 A 超限不影响租户 B")
    void tenantIsolation() {
        int perSecond = 3;
        // 租户 A 打满配额
        for (int i = 0; i < perSecond; i++) {
            assertTrue(rateLimiter.tryAcquire("t-a", "/api/v1/orders", perSecond, 6000));
        }
        assertFalse(rateLimiter.tryAcquire("t-a", "/api/v1/orders", perSecond, 6000),
                "Tenant A should be rate limited");

        // 租户 B 仍可请求
        for (int i = 0; i < perSecond; i++) {
            assertTrue(rateLimiter.tryAcquire("t-b", "/api/v1/orders", perSecond, 6000),
                    "Tenant B request " + i + " should pass");
        }
    }

    @Test
    @DisplayName("不同端点独立计数：同租户不同端点不互相影响")
    void endpointIsolation() {
        int perSecond = 3;
        // 端点 1 打满
        for (int i = 0; i < perSecond; i++) {
            assertTrue(rateLimiter.tryAcquire("t-a", "/api/v1/orders", perSecond, 6000));
        }
        assertFalse(rateLimiter.tryAcquire("t-a", "/api/v1/orders", perSecond, 6000));

        // 端点 2 仍可请求
        for (int i = 0; i < perSecond; i++) {
            assertTrue(rateLimiter.tryAcquire("t-a", "/api/v1/refunds", perSecond, 6000),
                    "Endpoint 2 request " + i + " should pass");
        }
    }

    @Test
    @DisplayName("tryAcquireWithDefault：使用默认配额")
    void tryAcquireWithDefaultUsesDefaultQuota() {
        // 默认 100/秒，连续 50 个应放行
        for (int i = 0; i < 50; i++) {
            assertTrue(rateLimiter.tryAcquireWithDefault("t-a", "/api/v1/orders"));
        }
    }

    @Test
    @DisplayName("分钟级超限：超过 perMinute 配额后拒绝")
    void perMinuteLimitExceeded() {
        int perMinute = 5;
        // 前 5 个放行（perSecond 设很大避免秒级触发）
        for (int i = 0; i < perMinute; i++) {
            assertTrue(rateLimiter.tryAcquire("t-a", "/api/v1/orders", 10000, perMinute),
                    "Request " + i + " should pass");
        }
        // 第 6 个拒绝（分钟级超限）
        assertFalse(rateLimiter.tryAcquire("t-a", "/api/v1/orders", 10000, perMinute),
                "Request 6 should be rejected by per-minute limit");
    }

    @Test
    @DisplayName("配额为 0：所有请求拒绝")
    void zeroQuotaRejectsAll() {
        assertFalse(rateLimiter.tryAcquire("t-a", "/api/v1/orders", 0, 0));
        assertFalse(rateLimiter.tryAcquire("t-a", "/api/v1/orders", 0, 0));
    }
}