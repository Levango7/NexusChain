package org.nexus.common.tracing;

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
 * <p>本类是跨模块共享实现（{@code nexus-common:tracing}），由
 * {@code nexus-gateway} / {@code nexus-bridge} / {@code nexus-signing-service}
 * 原先三份逐字节一致的拷贝合并而来，包名统一为 {@code org.nexus.common.tracing}。
 * 各消费模块通过 {@code implementation project(':nexus-common')} 引用，
 * 旧的模块内副本（{@code org.nexus.<module>.tracing.BusinessSpan}）已删除。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * try (BusinessSpan span = BusinessSpan.start(tracer, "payment.create")
 *         .attr("payment.id", paymentId)
 *         .attr("payment.amount", amount)) {
 *     // 业务逻辑
 *     span.attr("payment.tx.hash", txHash);  // 后续追加属性
 *     return result;
 * } catch (Exception e) {
 *     // BusinessSpan.close() 自动标记 error + 记录异常事件
 *     throw e;
 * }
 * }</pre>
 *
 * <p>当 {@code tracer == null}（测试环境 / tracing 未启用）时，
 * 所有操作降级为 no-op，保证业务逻辑与测试不受影响。</p>
 *
 * <p>线程安全：每个 {@link BusinessSpan} 实例仅限当前线程使用，
 * 不可跨线程传递（span 上下文绑定到当前线程的 Tracer）。</p>
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

    /**
     * 启动一个名为 {@code name} 的新业务 span。
     *
     * <p>若 {@code tracer == null}，返回 no-op span（所有操作空实现）。
     * 若当前线程已有活跃 span，新 span 作为其子 span（child of current）；
     * 否则作为根 span。</p>
     *
     * @param tracer tracing 端点（可为 {@code null}）
     * @param name   span 名称，遵循 {@code domain.action} 约定
     *               （如 {@code payment.create} / {@code bridge.lock}）
     * @return 已启动的 {@link BusinessSpan}，用于链式追加属性
     */
    public static BusinessSpan start(Tracer tracer, String name) {
        Objects.requireNonNull(name, "span name must not be null");
        if (tracer == null) {
            return new BusinessSpan(null, null, name);
        }
        Span span = tracer.nextSpan().name(name).start();
        return new BusinessSpan(tracer, span, name);
    }

    /**
     * 追加 span 属性（string 值）。
     *
     * @param key   属性键（遵循 {@code domain.field} 约定，如 {@code payment.id}）
     * @param value 属性值（{@code null} 时跳过）
     * @return this（链式调用）
     */
    public BusinessSpan attr(String key, String value) {
        if (span != null && value != null) {
            span.tag(key, value);
        }
        return this;
    }

    /**
     * 追加 span 属性（long 值）。
     */
    public BusinessSpan attr(String key, long value) {
        if (span != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * 追加 span 属性（double 值）。
     */
    public BusinessSpan attr(String key, double value) {
        if (span != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * 追加 span 属性（boolean 值）。
     */
    public BusinessSpan attr(String key, boolean value) {
        if (span != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * 追加 span 属性（Object 值，调用 {@code toString()}）。
     */
    public BusinessSpan attr(String key, Object value) {
        if (span != null && value != null) {
            span.tag(key, String.valueOf(value));
        }
        return this;
    }

    /**
     * 标记当前 span 为 ERROR 状态，并记录异常事件。
     *
     * <p>在 catch 块中显式调用，或在 {@link #close()} 时由
     * 异常标志自动触发。span 仍需 {@link #close()} 正式结束。</p>
     *
     * @param throwable 异常对象（可为 {@code null}，仅标记 error 状态）
     */
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

    /**
     * 显式标记业务成功（无异常）。
     *
     * <p>可选调用：{@link #close()} 时未调用 {@link #error(Throwable)}
     * 即视为成功。本方法用于在结束前追加 {@code status=ok} 属性。</p>
     */
    public BusinessSpan success() {
        if (span != null) {
            span.tag("status", "ok");
        }
        return this;
    }

    /**
     * 结束 span。
     *
     * <p>幂等：多次调用安全。在 try-with-resources 退出时自动调用。
     * 未显式 {@link #error(Throwable)} 的 span 视为成功结束。</p>
     */
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

    /**
     * 返回 span 名称（用于日志 / 调试）。
     */
    public String name() {
        return name;
    }

    /**
     * 返回底层 Micrometer Span（可为 {@code null}）。
     *
     * <p>暴露给需要直接操作 span 的高级场景（如自定义事件记录）。
     * 一般业务代码应通过 {@link #attr} / {@link #error} 方法操作。</p>
     */
    public Span rawSpan() {
        return span;
    }
}
