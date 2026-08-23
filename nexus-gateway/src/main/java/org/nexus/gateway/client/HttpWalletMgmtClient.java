package org.nexus.gateway.client;

import org.nexus.sdk.client.WalletMgmtClient;
import org.nexus.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 钱包管理服务 HTTP 客户端实现。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」从原 {@link ExchangeWalletClient} 拆分出的
 * 「钱包管理」边界客户端，实现 {@link WalletMgmtClient} 接口。</p>
 *
 * <p>承载不涉及私钥的钱包管理操作：地址校验、地址转公钥哈希。
 * 当前阶段仍通过 HTTP 调用 exchange-wallet 的端点，未来切换为
 * 调用独立部署的 nexus-wallet-service。</p>
 *
 * <p>原 {@link ExchangeWalletClient} 中的 {@code addressToPubkeyHash} /
 * {@code verifyAddress} 方法逻辑迁入本类，保持 REST 调用方式不变。</p>
 *
 * <p>Phase 1 任务 #55：gateway 已切换为 Feign 调用 nexus-wallet-service
 * （{@link org.nexus.sdk.client.feign.WalletMgmtFeignClient}）。本 HTTP 实现类
 * 保留作为 legacy/回滚备用，Resilience4j 注解保留（与 Sentinel 共存）。</p>
 *
 * <p>性能优化（任务 #310）：注入共享的连接池化 RestTemplate。</p>
 */
@Component
public class HttpWalletMgmtClient implements WalletMgmtClient {

    private static final Logger log = LoggerFactory.getLogger(HttpWalletMgmtClient.class);

    private final RestTemplate restTemplate;
    private final GatewayConfig gatewayConfig;

    @Autowired
    public HttpWalletMgmtClient(GatewayConfig gatewayConfig, RestTemplate restTemplate) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = restTemplate;
    }

    /** 测试用兼容构造器。 */
    public HttpWalletMgmtClient(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = new RestTemplate();
    }

    @Override
    @CircuitBreaker(name = "walletService", fallbackMethod = "addressToPubkeyHashFallback")
    public String addressToPubkeyHash(String address) {
        try {
            String baseUrl = gatewayConfig.getExchangeWallet().getBaseUrl();
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .pathSegment("addressToPubkeyHash")
                    .queryParam("address", address)
                    .toUriString();
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);

            if (resp.getBody() == null) return null;
            Object statusCode = resp.getBody().get("statusCode");
            if (statusCode instanceof Number && ((Number) statusCode).intValue() == 2000) {
                Object data = resp.getBody().get("data");
                return data != null ? data.toString() : null;
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Failed to convert address to pubkeyHash: {}", e.getMessage());
            return null;
        }
    }

    @Override
    @CircuitBreaker(name = "walletService", fallbackMethod = "verifyAddressFallback")
    public boolean verifyAddress(String address) {
        try {
            String baseUrl = gatewayConfig.getExchangeWallet().getBaseUrl();
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .pathSegment("verifyAddress")
                    .queryParam("address", address)
                    .toUriString();
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);

            if (resp.getBody() == null) return false;
            Object statusCode = resp.getBody().get("statusCode");
            return statusCode instanceof Number && ((Number) statusCode).intValue() == 2000;
        } catch (RuntimeException e) {
            log.warn("Failed to verify address: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAddressWhitelisted(String address) {
        // PoC 阶段：exchange-wallet 未暴露白名单查询端点，返回 false。
        // 未来 nexus-wallet-service 独立部署后通过 /api/v1/wallet/whitelist/check 查询。
        return false;
    }

    @Override
    public String getCustodyTier(String walletId) {
        // PoC 阶段：exchange-wallet 未暴露托管查询端点，返回 HOT。
        // 未来 nexus-wallet-service 独立部署后通过 /api/v1/wallet/custody 查询。
        return "HOT";
    }

    // --- Circuit breaker fallbacks ---

    private String addressToPubkeyHashFallback(String address, Throwable t) {
        log.warn("Circuit breaker fallback: addressToPubkeyHash, cause={}", t.getMessage());
        return null;
    }

    private boolean verifyAddressFallback(String address, Throwable t) {
        log.warn("Circuit breaker fallback: verifyAddress, cause={}", t.getMessage());
        return false;
    }
}