package org.nexus.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * NexusChain API Gateway 统一入口启动类。
 *
 * <p>P3-T2：基于 Spring Cloud Gateway（WebFlux + Reactor Netty）实现的统一 API 入口，
 * 替代 Istio Ingress Gateway 作为应用层入口，承担：</p>
 * <ul>
 *   <li>统一鉴权（API Key + HMAC 签名验证，{@code AuthenticationFilter}）</li>
 *   <li>统一限流（Redis 令牌桶，{@code RateLimitFilter}）</li>
 *   <li>请求日志（method + path + status + latency，{@code RequestLogFilter}）</li>
 *   <li>CORS 跨域（{@code CorsFilter}）</li>
 *   <li>路由转发至下游 4 个 Spring Boot 服务（{@code GatewayConfig}）</li>
 * </ul>
 *
 * <p>服务发现：通过 {@link EnableDiscoveryClient} 启用 Nacos 服务发现，
 * 路由使用 {@code lb://<service-name>} 协议由 Spring Cloud LoadBalancer 解析实例。</p>
 *
 * <p>组件扫描范围：默认扫描 {@code org.nexus.apigateway} 包及其子包，
 * 覆盖 config / filter 子包。</p>
 *
 * <p>SCA 集成：
 * <ul>
 *   <li>Nacos discovery + config：bootstrap.yml 配置，自动注册到 Nacos</li>
 *   <li>Spring Cloud Gateway：路由 DSL 在 {@code GatewayConfig} 中以 Java DSL 声明</li>
 *   <li>Reactive Redis：限流令牌桶使用 ReactiveRedisTemplate 操作</li>
 * </ul></p>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}