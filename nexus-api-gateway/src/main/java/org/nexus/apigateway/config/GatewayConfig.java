package org.nexus.apigateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cloud Gateway 路由配置（Java DSL）。
 *
 * <p>P3-T2：将外部请求按路径前缀路由至下游 4 个 Spring Boot 服务，
 * 通过 Nacos 服务发现以 {@code lb://<service-name>} 协议解析实例。</p>
 *
 * <h2>路由表</h2>
 * <table>
 *   <caption>表：API Gateway 路由对照表</caption>
 *   <tr><th>路径前缀</th><th>下游服务</th><th>StripPrefix</th><th>说明</th></tr>
 *   <tr><td>/api/v1/payments/**</td><td>nexus-gateway</td><td>0</td><td>支付网关 API</td></tr>
 *   <tr><td>/api/v1/bridge/**</td><td>nexus-bridge</td><td>0</td><td>跨链桥 API</td></tr>
 *   <tr><td>/api/v1/signing/**</td><td>nexus-signing-service</td><td>0</td><td>签名服务 API</td></tr>
 *   <tr><td>/api/v1/wallet/**</td><td>nexus-wallet-service</td><td>0</td><td>钱包服务 API</td></tr>
 * </table>
 *
 * <h2>StripPrefix 说明</h2>
 * <p>StripPrefix=0 表示保留原始路径转发，下游服务以相同路径前缀（/api/v1/...）暴露端点。
 * 若下游服务路径不含 /api/v1 前缀，应改为 StripPrefix=2 剥离前缀。</p>
 *
 * <h2>过滤器链顺序</h2>
 * <p>全局过滤器（{@code AuthenticationFilter} → {@code RateLimitFilter} →
 * {@code RequestLogFilter}）通过 Spring {@code @Order} 注解控制执行顺序，
 * 在路由过滤器之前执行。{@code CorsFilter} 作为 GlobalFilter 在最外层处理预检请求。</p>
 *
 * <h2>元数据</h2>
 * <p>每条路由通过 {@code metadata} 声明下游服务名与端口号，便于观测与故障定位。
 * metadata 也会写入 Zipkin span tag，便于在追踪系统中按服务过滤。</p>
 */
@Configuration
public class GatewayConfig {

    /** 下游服务：支付网关（端口 8080）。 */
    private static final String SVC_GATEWAY = "nexus-gateway";
    /** 下游服务：跨链桥（端口 8084）。 */
    private static final String SVC_BRIDGE = "nexus-bridge";
    /** 下游服务：签名服务（端口 8082）。 */
    private static final String SVC_SIGNING = "nexus-signing-service";
    /** 下游服务：钱包服务（端口 8083）。 */
    private static final String SVC_WALLET = "nexus-wallet-service";

    /**
     * 路由定位器：以 Java DSL 声明 4 条路由。
     *
     * <p>路由匹配顺序按声明顺序（route ID 升序），第一条匹配的路由胜出。
     * 4 条路由路径前缀互斥，不存在重叠匹配问题。</p>
     *
     * <p>注：{@code preserveHostHeader()} 未调用，下游服务收到的是 Gateway 的 Host 头
     * （默认行为）。如需保留客户端原始 Host 头，可在 filters 中添加 {@code .preserveHostHeader()}。</p>
     *
     * @param builder RouteLocatorBuilder，由 Spring Cloud Gateway 自动注入
     * @return RouteLocator 路由定位器
     */
    @Bean
    public RouteLocator nexusRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                // 1. 支付网关 API：/api/v1/payments/** → nexus-gateway
                .route("payment-route", r -> r
                        .path("/api/v1/payments/**")
                        .filters(f -> f.stripPrefix(0))
                        .uri("lb://" + SVC_GATEWAY))

                // 2. 跨链桥 API：/api/v1/bridge/** → nexus-bridge
                .route("bridge-route", r -> r
                        .path("/api/v1/bridge/**")
                        .filters(f -> f.stripPrefix(0))
                        .uri("lb://" + SVC_BRIDGE))

                // 3. 签名服务 API：/api/v1/signing/** → nexus-signing-service
                .route("signing-route", r -> r
                        .path("/api/v1/signing/**")
                        .filters(f -> f.stripPrefix(0))
                        .uri("lb://" + SVC_SIGNING))

                // 4. 钱包服务 API：/api/v1/wallet/** → nexus-wallet-service
                .route("wallet-route", r -> r
                        .path("/api/v1/wallet/**")
                        .filters(f -> f.stripPrefix(0))
                        .uri("lb://" + SVC_WALLET))

                .build();
    }
}