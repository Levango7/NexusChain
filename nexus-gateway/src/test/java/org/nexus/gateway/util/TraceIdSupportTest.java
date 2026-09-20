package org.nexus.gateway.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 #22a 回归测试（2026-09-21）：错误/成功响应中的 traceId 必须是真实链路 ID。
 *
 * <p>原缺陷：{@code ApiResponse} 与 {@code V2ErrorResponse} 各自用
 * {@code UUID.randomUUID()} 生成 traceId，与 Micrometer Tracing 无任何关联，
 * 调用方拿它在链路系统里查不到东西。本类验证修复后确实读取 MDC。</p>
 */
class TraceIdSupportTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 中存在 traceId 时返回该真实值")
    void returnsRealTraceIdFromMdc() {
        MDC.put("traceId", "a1b2c3d4e5f6a7b8");

        assertEquals("a1b2c3d4e5f6a7b8", TraceIdSupport.currentTraceId());
        assertTrue(TraceIdSupport.hasRealTraceContext());
    }

    @Test
    @DisplayName("MDC 使用下划线键名时同样能读到")
    void supportsUnderscoreKeyVariant() {
        MDC.put("trace_id", "deadbeefcafe0001");

        assertEquals("deadbeefcafe0001", TraceIdSupport.currentTraceId());
        assertTrue(TraceIdSupport.hasRealTraceContext());
    }

    @Test
    @DisplayName("无 tracing 上下文时回退为 16 位关联 ID，且 hasRealTraceContext 为 false")
    void fallsBackWhenNoTraceContext() {
        String fallback = TraceIdSupport.currentTraceId();

        assertNotNull(fallback);
        assertEquals(16, fallback.length(), "回退值应为 16 位，与既有响应格式一致");
        assertTrue(fallback.matches("[0-9a-f]{16}"), "回退值应为十六进制，实际=" + fallback);
        // 关键：必须能被识别为「非真实链路 ID」，否则又会误导排查
        assertFalse(TraceIdSupport.hasRealTraceContext());
    }

    @Test
    @DisplayName("MDC 中 traceId 为空串时视为无上下文")
    void blankTraceIdIsTreatedAsAbsent() {
        MDC.put("traceId", "   ");

        assertFalse(TraceIdSupport.hasRealTraceContext());
        assertNotNull(TraceIdSupport.currentTraceId());
    }

    @Test
    @DisplayName("回退值每次不同，避免被误当作同一链路的标识")
    void fallbackIsNotConstant() {
        assertNotEquals(TraceIdSupport.currentTraceId(), TraceIdSupport.currentTraceId());
    }
}
