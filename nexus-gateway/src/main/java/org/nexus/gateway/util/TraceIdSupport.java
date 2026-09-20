package org.nexus.gateway.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 真实分布式追踪 ID 的读取入口（P1 #22a，2026-09-21 修复）。
 *
 * <p><b>背景缺陷</b>：{@code ApiResponse} 与 {@code V2ErrorResponse} 此前各自用
 * {@code UUID.randomUUID().substring(0,16)} 生成 traceId，与 Micrometer Tracing
 * <b>没有任何关联</b>。而 {@code V2ErrorResponse} 的 javadoc 还写着
 * "traceId：分布式追踪 ID，与 Micrometer Tracing 对齐" —— 文档在误导。
 * 后果：调用方拿到的 traceId 在日志/链路系统中<b>查不到任何东西</b>，
 * 排查时会把时间浪费在一个看似有效的 ID 上。这比不返回 traceId 更糟。</p>
 *
 * <p><b>修复</b>：优先读取 MDC 中的真实 traceId（Micrometer Tracing 的 Brave
 * 桥接会自动写入 {@code traceId}/{@code spanId}）。仅当当前线程无 tracing
 * 上下文时（如 NoopTracer 环境、非 Web 线程）才回退为随机关联 ID，
 * 且该回退值仅用于单次响应的关联，不代表可查询的链路 ID。</p>
 */
public final class TraceIdSupport {

    /** Micrometer Tracing / Brave 写入 MDC 的键名。 */
    private static final String MDC_KEY_TRACE_ID = "traceId";

    /** 部分日志配置使用下划线形式。 */
    private static final String MDC_KEY_TRACE_ID_ALT = "trace_id";

    private TraceIdSupport() {
    }

    /**
     * 返回当前线程的真实追踪 ID；无 tracing 上下文时返回随机关联 ID。
     *
     * <p>返回值保证非 null、非空白，可直接放入响应体。</p>
     */
    public static String currentTraceId() {
        String traceId = MDC.get(MDC_KEY_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get(MDC_KEY_TRACE_ID_ALT);
        }
        if (traceId != null && !traceId.isBlank()) {
            return traceId;
        }
        return fallbackCorrelationId();
    }

    /**
     * 判断当前是否处于真实 tracing 上下文中。
     *
     * <p>用于日志/诊断：为 false 时说明返回的 traceId 是本地回退值，
     * 在链路系统中不可查询。</p>
     */
    public static boolean hasRealTraceContext() {
        String traceId = MDC.get(MDC_KEY_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = MDC.get(MDC_KEY_TRACE_ID_ALT);
        }
        return traceId != null && !traceId.isBlank();
    }

    private static String fallbackCorrelationId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
