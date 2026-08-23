package org.nexus.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestTemplate 连接池配置（性能优化）。
 *
 * <p>统一为所有使用 RestTemplate 的客户端（ChainRpcClient、ConsortiumRpcClient、
 * HttpSigningServiceClient、HttpWalletMgmtClient、PaymentEventListener、
 * ChainNodeHealthIndicator、WebhookDeliveryService 等）提供共享的连接池化 RestTemplate，
 * 替代各处 {@code new RestTemplate()} 创建的无连接池实例。</p>
 *
 * <h3>实现方案</h3>
 * <p>使用 JDK 内置的 {@link java.net.http.HttpClient}（Java 11+）作为底层 HTTP 引擎，
 * 通过 {@link JdkClientHttpRequestFactory} 适配到 RestTemplate。
 * JDK HttpClient 自带连接池（keep-alive 连接复用），无需引入第三方依赖。</p>
 *
 * <h3>性能收益</h3>
 * <ul>
 *   <li><b>连接复用</b>：JDK HttpClient 自动复用 keep-alive 连接，
 *       避免每次请求重新建立连接（TLS 握手 ~50ms + TCP 三次握手 ~10ms）。
 *       对链节点 RPC（每笔支付确认查询 1-2 次）和 Webhook 投递（每事件 1 次 POST）
 *       收益显著。</li>
 *   <li><b>超时控制</b>：连接超时 5s、读取超时 30s，避免线程长时间挂起。</li>
 *   <li><b>零新依赖</b>：基于 JDK 内置 HttpClient，不引入任何第三方库。</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * @Component
 * public class SomeClient {
 *     private final RestTemplate restTemplate;
 *
 *     public SomeClient(RestTemplate restTemplate) {  // 注入共享实例
 *         this.restTemplate = restTemplate;
 *     }
 * }
 * }</pre>
 *
 * <p>各客户端可继续保留无参构造器（测试用），生产构造器优先注入共享 RestTemplate。</p>
 *
 * @since 性能优化任务 #310
 */
@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    /** 连接建立超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 读取超时（毫秒）—— 通用上限，敏感调用方可更短。 */
    private static final int READ_TIMEOUT_MS = 30000;

    /**
     * 共享的连接池化 RestTemplate Bean。
     *
     * <p>使用 {@link JdkClientHttpRequestFactory} 包装 JDK {@link java.net.http.HttpClient}，
     * 启用连接池（keep-alive 复用）。{@code @ConditionalOnMissingBean} 保证测试可注入
     * 自定义 RestTemplate（如 WireMock）。</p>
     *
     * @return 共享的 RestTemplate 实例（连接池化）
     */
    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        RestTemplate rt = new RestTemplate(buildPooledRequestFactory());
        log.info("Pooled RestTemplate initialized with JDK HttpClient: connectTimeout={}ms, readTimeout={}ms",
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        return rt;
    }

    /**
     * 构建带连接池的 {@link ClientHttpRequestFactory}。
     *
     * <p>配置 JDK HttpClient：
     * <ul>
     *   <li>连接超时 5s、读取超时 30s</li>
     *   <li>HTTP/2 优先（同 host 多路复用，进一步降低连接数）</li>
     *   <li>keep-alive 连接复用（JDK HttpClient 内置连接池）</li>
     *   <li>重定向跟随（兼容 302/307 回调场景）</li>
     * </ul>
     * </p>
     */
    private ClientHttpRequestFactory buildPooledRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(READ_TIMEOUT_MS));
        return factory;
    }
}
