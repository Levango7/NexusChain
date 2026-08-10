package org.nexus.apigateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * CORS 跨域全局过滤器。
 *
 * <p>P3-T2：在过滤器链最外层处理 CORS 预检（OPTIONS）与跨域响应头注入。
 * 相比 Spring Cloud Gateway 内置的 {@code CorsWebFilter}，本过滤器提供更细粒度的
 * 配置控制（按环境覆盖 allowed-origins）与统一的日志观测。</p>
 *
 * <h2>处理流程</h2>
 * <ol>
 *   <li>请求无 Origin 头 → 非跨域请求，直接放行。</li>
 *   <li>Origin 不在 allowed-origins 白名单 → 不注入 CORS 头（浏览器自然拒绝）。</li>
 *   <li>OPTIONS 预检 → 注入 CORS 头后直接返回 204，不进入下游。</li>
 *   <li>其他方法 → 注入 CORS 头后继续过滤器链。</li>
 * </ol>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.api-gateway.cors.allowed-origins}：允许的 Origin 列表（逗号分隔），
 *       默认 {@code *}（开发环境）。生产环境必须显式列出商户域名。</li>
 *   <li>{@code nexus.api-gateway.cors.allowed-methods}：允许的 HTTP 方法（逗号分隔）</li>
 *   <li>{@code nexus.api-gateway.cors.allowed-headers}：允许的请求头（逗号分隔）</li>
 *   <li>{@code nexus.api-gateway.cors.exposed-headers}：暴露给浏览器的响应头</li>
 *   <li>{@code nexus.api-gateway.cors.allow-credentials}：是否允许携带凭证</li>
 *   <li>{@code nexus.api-gateway.cors.max-age}：预检缓存时长（秒）</li>
 * </ul>
 *
 * <h2>安全注意</h2>
 * <p>当 {@code allow-credentials=true} 时，allowed-origins 不能为 {@code *}，
 * 必须显式列出域名（CORS 规范要求）。本过滤器在 {@code *} + credentials=true 时
 * 自动回退为不注入 {@code Access-Control-Allow-Origin} 头（拒绝跨域）。</p>
 */
@Component
public class CorsFilter implements GlobalFilter, Ordered {

    /** CORS 预检缓存默认时长（秒）。 */
    private static final long DEFAULT_MAX_AGE = 3600L;

    /** 允许的 Origin 列表（逗号分隔）。 */
    @Value("${nexus.api-gateway.cors.allowed-origins:*}")
    private String allowedOriginsConfig;

    /** 允许的 HTTP 方法（逗号分隔）。 */
    @Value("${nexus.api-gateway.cors.allowed-methods:GET,POST,PUT,DELETE,PATCH,OPTIONS}")
    private String allowedMethodsConfig;

    /** 允许的请求头（逗号分隔）。 */
    @Value("${nexus.api-gateway.cors.allowed-headers:*}")
    private String allowedHeadersConfig;

    /** 暴露给浏览器的响应头（逗号分隔）。 */
    @Value("${nexus.api-gateway.cors.exposed-headers:X-Request-Id,X-RateLimit-Limit,X-RateLimit-Remaining}")
    private String exposedHeadersConfig;

    /** 是否允许携带凭证。 */
    @Value("${nexus.api-gateway.cors.allow-credentials:false}")
    private boolean allowCredentials;

    /** 预检缓存时长（秒）。 */
    @Value("${nexus.api-gateway.cors.max-age:3600}")
    private long maxAge;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        ServerHttpResponse response = exchange.getResponse();
        HttpHeaders headers = request.getHeaders();
        String origin = headers.getFirst(HttpHeaders.ORIGIN);

        // 非跨域请求：无 Origin 头
        if (!StringUtils.hasText(origin)) {
            return chain.filter(exchange);
        }

        // Origin 校验：白名单匹配
        if (!isOriginAllowed(origin)) {
            // 不在白名单：不注入 CORS 头，浏览器自然拒绝
            return chain.filter(exchange);
        }

        // 注入 CORS 响应头
        HttpHeaders responseHeaders = response.getHeaders();
        responseHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        responseHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, String.valueOf(allowCredentials));
        if (StringUtils.hasText(exposedHeadersConfig)) {
            responseHeaders.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, exposedHeadersConfig);
        }

        // OPTIONS 预检：直接返回 204，不进入下游
        if (request.getMethod() == HttpMethod.OPTIONS) {
            responseHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, allowedMethodsConfig);
            responseHeaders.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, allowedHeadersConfig);
            responseHeaders.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, String.valueOf(maxAge));
            response.setStatusCode(HttpStatus.NO_CONTENT);
            return response.setComplete();
        }

        return chain.filter(exchange);
    }

    /**
     * 全局过滤器顺序：最外层（-400），先于 RequestLogFilter（-300）执行。
     * <p>确保 CORS 预检在日志记录之前直接返回，且 CORS 头注入在所有响应上。</p>
     */
    @Override
    public int getOrder() {
        return -400;
    }

    /**
     * 判断 Origin 是否在允许列表中。
     * <p>支持精确匹配与通配符 {@code *}。当 {@code allow-credentials=true} 且配置为 {@code *} 时，
     * 按 CORS 规范要求返回 false（拒绝），避免浏览器报错。</p>
     */
    private boolean isOriginAllowed(String origin) {
        if (!StringUtils.hasText(allowedOriginsConfig)) {
            return false;
        }
        List<String> allowed = Arrays.asList(allowedOriginsConfig.split(","));
        // 通配符 *
        if (allowed.contains("*")) {
            // CORS 规范：credentials=true 时 origin 不能为 *
            if (allowCredentials) {
                return false;
            }
            return true;
        }
        // 精确匹配
        for (String allowedOrigin : allowed) {
            if (origin.equalsIgnoreCase(allowedOrigin.trim())) {
                return true;
            }
        }
        return false;
    }
}