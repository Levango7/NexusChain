package org.nexus.signing.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.util.Objects;

/**
 * 业务 span 自动管理工具（P3-T5：分布式追踪深化）。
 *
 * <p>封装 {@link Tracer#nextSpan()} 手动 span 创建，支持 try-with-resources
 * 模式，避免依赖 {@code @WithSpan} 注解所需的
 * {@code micrometer-tracing-annotation} + AOP 额外依赖。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (BusinessSpan span = BusinessSpan.start(tracer, "signing.mpc.round")
 *         .attr("signing.round.id", sessionId)
 *         .attr("signing.round.index", roundIndex)) {
 *     // MPC 单轮计算
 * }
 * }</pre>
 *
 * <p>当 {@code tracer == null}（测试环境 / tracing 未启用）时，
 * 所有操作降级为 no-op，保证业务逻辑与测试不受影响。</p>
 */
public final class BusinessSpan implements AutoCloseable {

    private final Tracer tracer;
    private final Span span;
    private final String name;
    private boolean closed = false;

    private BusinessSpan(Tracer tracer, Span span, String name) {
        this.tracer = tracer;
        this.span = span;
        this.name = name;
    }

    public static BusinessSpan start(Tracer tracer, String name) {
        Objects.requireNonNull(name, "span name must not be null");
        if (tracer == null) {
            return new BusinessSpan(null, null, name);
        }
        Span span = tracer.nextSpan().name(name).start();
        return new BusinessSpan(tracer, span, name);
    }

    public BusinessSpan attr(String key, String value) {
        if (span != null && value != null) {
            span.tag(key, value);
        }
        return this;
    }

    public BusinessSpan attr(String key, long value) {
        if (span != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    public BusinessSpan attr(String key, double value) {
        if (span != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    public BusinessSpan attr(String key, boolean value) {
        if (span != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    public BusinessSpan attr(String key, Object value) {
        if (span != null && value != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    public BusinessSpan error(Throwable throwable) {
        if (span != null) {
            if (throwable != null) {
                span.error(throwable);
            } else {
                span.tag("error", "true");
            }
        }
        return this;
    }

    public BusinessSpan success() {
        if (span != null) {
            span.tag("status", "ok");
        }
        return this;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (span != null) {
            span.end();
        }
    }

    public String name() {
        return name;
    }

    public Span rawSpan() {
        return span;
    }
}