package org.nexus.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

/**
 * 统一限流全局过滤器：基于 Redis 令牌桶算法。
 *
 * <p>P3-T2：在鉴权之后、路由转发之前对每个请求执行限流。
 * 采用 Redis Lua 脚本实现原子令牌桶，确保多实例 Gateway 限流计数一致。</p>
 *
 * <h2>限流维度</h2>
 * <p>按 API Key 限流（{@code X-NexusChain-ApiKey} 头），未携带 API Key 时按客户端 IP 兜底。
 * 同一 API Key 的所有请求共享一个令牌桶。</p>
 *
 * <h2>令牌桶参数</h2>
 * <ul>
 *   <li>容量（capacity）：桶最大令牌数，对应突发流量上限</li>
 *   <li>补充速率（refillRate）：每秒补充令牌数，对应稳态 QPS 上限</li>
 * </ul>
 *
 * <p>默认 capacity=100，refillRate=10，即允许 100 的突发，稳态 10 QPS。
 * 可通过 Nacos 配置 {@code nexus.api-gateway.ratelimit.*} 覆盖。</p>
 *
 * <h2>Redis Lua 脚本</h2>
 * <p>令牌桶算法通过单条 Lua 脚本在 Redis 端原子执行：
 * 读取当前令牌数与上次补充时间戳 → 按经过时间补充令牌 → 判断是否足够 →
 * 扣减并写回 → 返回是否放行。脚本入参：</p>
 * <ul>
 *   <li>KEYS[1]：限流 key（如 {@code ratelimit:apikey:<key>}）</li>
 *   <li>ARGV[1]：容量</li>
 *   <li>ARGV[2]：补充速率（令牌/秒）</li>
 *   <li>ARGV[3]：当前时间戳（秒）</li>
 *   <li>ARGV[4]：桶内令牌数 key 后缀（用于 hash field）</li>
 *   <li>ARGV[5]：桶上次补充时间戳 key 后缀</li>
 * </ul>
 *
 * <h2>失败响应</h2>
 * <p>限流触发返回 HTTP 429 Too Many Requests，响应头 {@code X-RateLimit-Remaining: 0}，
 * 响应体 JSON {@code {"code":"RATE_LIMITED","message":"too many requests"}}。</p>
 *
 * <h2>Redis 不可用降级</h2>
 * <p>Redis 连接异常时降级为<b>放行</b>（fail-open），仅记录 WARN 日志。
 * 理由：限流为保护性措施，不应因基础设施故障导致全部请求被拒；
 * 下游服务自身也有 Sentinel 兜底。生产环境可通过 {@code fail-closed=true} 切换为拒绝策略。</p>
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** API Key 请求头名（与 AuthenticationFilter 一致）。 */
    private static final String HEADER_API_KEY = "X-NexusChain-ApiKey";
    /** 限流 key 前缀。 */
    private static final String KEY_PREFIX = "ratelimit:apikey:";
    /** Redis hash field：当前令牌数。 */
    private static final String FIELD_TOKENS = "tokens";
    /** Redis hash field：上次补充时间戳。 */
    private static final String FIELD_TIMESTAMP = "ts";

    /**
     * 令牌桶 Lua 脚本：原子地补充令牌、判断是否放行、扣减写回。
     * <p>返回值：1 = 放行，0 = 拒绝。</p>
     */
    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local tokens_key = ARGV[4]
            local ts_key = ARGV[5]

            local tokens = tonumber(redis.call('HGET', key, tokens_key)) or capacity
            local last_ts = tonumber(redis.call('HGET', key, ts_key)) or now

            -- 按经过时间补充令牌，不超过容量
            local delta = math.max(0, now - last_ts) * refill_rate
            tokens = math.min(capacity, tokens + delta)

            if tokens >= 1 then
                tokens = tokens - 1
                redis.call('HMSET', key, tokens_key, tokens, ts_key, now)
                redis.call('EXPIRE', key, 600)
                return 1
            else
                redis.call('HMSET', key, tokens_key, tokens, ts_key, now)
                redis.call('EXPIRE', key, 600)
                return 0
            end
            """;

    /** Reactive Redis 模板。 */
    private final ReactiveStringRedisTemplate redisTemplate;
    /** 预编译 Lua 脚本。 */
    private final RedisScript<Long> rateLimitScript;

    /** 令牌桶容量（突发上限）。 */
    @Value("${nexus.api-gateway.ratelimit.capacity:100}")
    private long capacity;

    /** 令牌补充速率（令牌/秒，稳态 QPS）。 */
    @Value("${nexus.api-gateway.ratelimit.refill-rate:10}")
    private long refillRate;

    /** Redis 不可用时是否拒绝（fail-closed）。默认 false（fail-open 放行）。 */
    @Value("${nexus.api-gateway.ratelimit.fail-closed:false}")
    private boolean failClosed;

    /** 限流开关。 */
    @Value("${nexus.api-gateway.ratelimit.enabled:true}")
    private boolean enabled;

    /** 是否信任代理转发的 X-Forwarded-For（仅可信 LB/CDN 置 true，防客户端伪造）。 */
    @Value("${nexus.api-gateway.ratelimit.trusted-proxy:false}")
    private boolean trustedProxy;

    public RateLimitFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        // 通过 DefaultRedisScript 预编译，避免每次执行都发送脚本内容
        org.springframework.data.redis.core.script.DefaultRedisScript<Long> script =
                new org.springframework.data.redis.core.script.DefaultRedisScript<>();
        script.setScriptText(LUA_SCRIPT);
        script.setResultType(Long.class);
        this.rateLimitScript = script;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String identity = resolveIdentity(request);
        String redisKey = KEY_PREFIX + identity;
        long now = System.currentTimeMillis() / 1000L;

        List<String> keys = Collections.singletonList(redisKey);
        List<Object> args = List.of(
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now),
                FIELD_TOKENS,
                FIELD_TIMESTAMP);

        return redisTemplate.execute(rateLimitScript, keys, args)
                .next() // 取第一个返回值
                .cast(Long.class)
                .map(allowed -> allowed != null && allowed == 1L)
                .flatMap(allowed -> {
                    if (allowed) {
                        exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(capacity));
                        return chain.filter(exchange);
                    }
                    log.warn("限流触发：identity={}, path={}", identity,
                            request.getPath().pathWithinApplication().value());
                    return reject(exchange);
                })
                .onErrorResume(ex -> {
                    // Redis 不可用降级
                    log.warn("Redis 限流异常，降级处理：identity={}, failClosed={}", identity, failClosed, ex);
                    if (failClosed) {
                        return reject(exchange);
                    }
                    return chain.filter(exchange);
                });
    }

    /**
     * 全局过滤器顺序：在 AuthenticationFilter（-200）之后、RequestLogFilter（-50）之前。
     */
    @Override
    public int getOrder() {
        return -150;
    }

    /**
     * 解析限流身份：优先 API Key，未携带时用客户端 IP 兜底。
     */
    private String resolveIdentity(ServerHttpRequest request) {
        String apiKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isEmpty()) {
            return "apikey:" + apiKey;
        }
        // 客户端 IP：仅当 trustedProxy=true 时信任 X-Forwarded-For（经可信 LB / CDN），
        // 否则直接使用 remoteAddress，防止客户端伪造 X-Forwarded-For 绕过限流
        if (trustedProxy) {
            String xff = request.getHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return "ip:" + xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddress() != null
                ? "ip:" + request.getRemoteAddress().getAddress().getHostAddress()
                : "ip:unknown";
    }

    /** 返回 429 Too Many Requests 响应。 */
    private Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", "0");
        String body = "{\"code\":\"RATE_LIMITED\",\"message\":\"too many requests\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }
}