package org.nexus.gateway.observability;

import org.springframework.context.annotation.Configuration;

/**
 * 链路追踪配置（Phase 3 任务 #61 改造，设计文档 §4.3.3）。
 *
 * <p>Phase 1+2 的手动 X-NexusChain-Trace-Id filter 已删除，
 * 改由 Micrometer Tracing + Brave 自动埋点：
 * <ul>
 *   <li>入口：HttpServerTracingHandler 自动生成 / 透传 traceparent header（W3C）</li>
 *   <li>Feign 调用：FeignClientTracingHandler 自动注入 traceparent header</li>
 *   <li>日志：Micrometer Tracing 自动将 traceId/spanId 放入 MDC（slf4j MDC 集成）</li>
 *   <li>上报：ZipkinReporter 异步上报 span 到 Zipkin Server</li>
 * </ul></p>
 *
 * <p>依赖见 build.gradle：
 * {@code io.micrometer:micrometer-tracing-bridge-brave}（Brave 桥接，W3C traceparent）+
 * {@code io.zipkin.reporter2:zipkin-reporter-brave}（Zipkin 上报）+
 * {@code io.github.openfeign:feign-micrometer}（Feign 自动埋点）。</p>
 *
 * <p>本类保留为占位，未来如需自定义 baggage 传播（orderId 等）在此扩展。</p>
 */
@Configuration
public class TracingConfig {
    // Micrometer Tracing 自动配置，无需手动 Bean。
    // 未来如需自定义 baggage：
    // @Bean
    // public BaggageManager baggageManager() { ... }
}
