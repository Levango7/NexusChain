package org.nexus.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求日志全局过滤器：记录 method + path + status + latency + requestId。
 *
 * <p>P3-T2：在过滤器链最外层（鉴权/限流之前）记录请求开始时间，
 * 在响应返回时计算耗时并输出结构化日志，便于观测与故障定位。</p>
 *
 * <h2>日志字段</h2>
 * <ul>
 *   <li>{@code requestId}：本次请求唯一 ID（UUID），写入响应头 {@code X-Request-Id} 便于客户端反馈</li>
 *   <li>{@code method}：HTTP 方法</li>
 *   <li>{@code path}：请求路径</li>
 *   <li>{@code status}：响应 HTTP 状态码</li>
 *   <li>{@code latencyMs}：本网关处理耗时（毫秒，不含下游服务时间）</li>
 *   <li>{@code downstream}：路由命中的下游服务名（从 exchange 属性读取）</li>
 * </ul>
 *
 * <h2>日志格式</h2>
 * <p>使用 INFO 级别输出，格式：</p>
 * <pre>请求完成 requestId={} method={} path={} status={} latencyMs={} downstream={}</pre>
 *
 * <h2>过滤器顺序</h2>
 * <p>使用 {@code -300} 在 AuthenticationFilter（-200）之前执行，确保所有请求
 * （含鉴权失败的 401）都被记录。响应阶段通过 {@code Mono.doFinally} 在链末尾执行，
 * 不影响后续过滤器顺序。</p>
 *
 * <h2>性能考量</h2>
 * <p>仅记录 INFO 级别日志，不读请求体，不阻塞响应流。{@code System.nanoTime()} 精度足够
 * 网关层耗时统计（微秒级），生产环境如需更高精度可改用 Micrometer Timer。</p>
 */
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /** 请求开始时间戳 attribute key。 */
    private static final String ATTR_START_TIME = "nexus.request.startTime";
    /** 请求 ID attribute key。 */
    private static final String ATTR_REQUEST_ID = "nexus.request.id";
    /** 响应头：请求 ID。 */
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        String method = request.getMethod().name();

        // 生成 requestId 并写入请求头（透传至下游，便于全链路追踪关联）
        String incomingRequestId = request.getHeaders().getFirst(HEADER_REQUEST_ID);
        final String requestId = (incomingRequestId == null || incomingRequestId.isEmpty())
                ? UUID.randomUUID().toString()
                : incomingRequestId;
        exchange.getAttributes().put(ATTR_REQUEST_ID, requestId);
        exchange.getAttributes().put(ATTR_START_TIME, System.nanoTime());

        // 透传 requestId 至下游
        ServerHttpRequest mutated = request.mutate()
                .header(HEADER_REQUEST_ID, requestId)
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();

        return chain.filter(mutatedExchange)
                .doFinally(signal -> {
                    long startNanos = (long) mutatedExchange.getAttributes().getOrDefault(ATTR_START_TIME, 0L);
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
                    int status = mutatedExchange.getResponse().getStatusCode() != null
                            ? mutatedExchange.getResponse().getStatusCode().value()
                            : 0;
                    String downstream = (String) mutatedExchange.getAttributes()
                            .getOrDefault("org.springframework.cloud.gateway.support.ServerWebExchangeUtils.gatewayRouteRouteId", "unknown");

                    log.info("请求完成 requestId={} method={} path={} status={} latencyMs={} downstream={}",
                            requestId, method, path, status, latencyMs, downstream);
                });
    }

    /**
     * 全局过滤器顺序：最外层（-300），先于 AuthenticationFilter（-200）执行。
     */
    @Override
    public int getOrder() {
        return -300;
    }
}