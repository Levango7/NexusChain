package org.nexus.gateway.client;

import org.nexus.sdk.client.SigningServiceClient;
import org.nexus.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 签名服务 HTTP 客户端实现。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」从原 {@link ExchangeWalletClient} 拆分出的
 * 「签名」边界客户端，实现 {@link SigningServiceClient} 接口。</p>
 *
 * <p>承载涉及私钥的敏感操作：平台密钥库签名 + 广播、MPC 阈值签名。
 * 调用方不接触私钥，由签名服务使用服务端密钥库完成签名。
 * 当前阶段仍通过 HTTP 调用 exchange-wallet 的端点，未来切换为
 * 调用独立部署的 nexus-signing-service。</p>
 *
 * <p>原 {@link ExchangeWalletClient} 中的 {@code transfer} /
 * {@code signTransfer} 方法逻辑迁入本类，保持 REST 调用方式不变。</p>
 */
@Component
public class HttpSigningServiceClient implements SigningServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpSigningServiceClient.class);

    private final RestTemplate restTemplate;
    private final GatewayConfig gatewayConfig;

    public HttpSigningServiceClient(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = new RestTemplate();
    }

    @Override
    @CircuitBreaker(name = "walletService", fallbackMethod = "signTransferFallback")
    @Retry(name = "walletService")
    public String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
        try {
            String baseUrl = gatewayConfig.getExchangeWallet().getBaseUrl();
            String url = baseUrl + gatewayConfig.getExchangeWallet().getSignPath();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("fromPubkey", fromPubkey);
            params.add("toPubkeyHash", toPubkeyHash);
            params.add("amount", amount.toPlainString());

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);

            if (resp.getBody() == null) {
                log.error("Exchange-wallet sign returned empty response");
                return null;
            }

            Object statusCode = resp.getBody().get("statusCode");
            if (statusCode instanceof Number && ((Number) statusCode).intValue() == 2000) {
                Object data = resp.getBody().get("data");
                String txHash = data != null ? data.toString() : null;
                log.info("Sign+transfer successful: txHash={}", txHash);
                return txHash;
            } else {
                log.error("Sign+transfer failed: response={}", resp.getBody());
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to call exchange-wallet sign: {}", e.getMessage());
            return null;
        }
    }

    @Override
    @CircuitBreaker(name = "walletService", fallbackMethod = "transferFallback")
    @Retry(name = "walletService")
    public String transfer(String fromPubkey, String toPubkeyHash, BigDecimal amount, String privateKey) {
        try {
            String baseUrl = gatewayConfig.getExchangeWallet().getBaseUrl();
            String url = baseUrl + "/ClientToTransferAccount";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("fromPubkey", fromPubkey);
            params.add("toPubkeyHash", toPubkeyHash);
            params.add("amount", amount.toPlainString());
            params.add("prikey", privateKey);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);

            if (resp.getBody() == null) {
                log.error("Exchange-wallet returned empty response");
                return null;
            }

            Object statusCode = resp.getBody().get("statusCode");
            if (statusCode instanceof Number && ((Number) statusCode).intValue() == 2000) {
                Object data = resp.getBody().get("data");
                String txHash = data != null ? data.toString() : null;
                log.info("Transfer successful: txHash={}", txHash);
                return txHash;
            } else {
                log.error("Transfer failed: response={}", resp.getBody());
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to call exchange-wallet transfer: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean canSignViaMpc(BigDecimal amount) {
        // PoC 阶段：exchange-wallet 未暴露 MPC 能力查询端点，返回 false。
        // 未来 nexus-signing-service 独立部署后通过 /api/v1/signing/capability 查询。
        return false;
    }

    // --- Circuit breaker fallbacks ---

    private String transferFallback(String fromPubkey, String toPubkeyHash, BigDecimal amount, String privateKey, Throwable t) {
        log.error("Circuit breaker fallback: transfer failed, cause={}", t.getMessage());
        return null;
    }

    private String signTransferFallback(String fromPubkey, String toPubkeyHash, BigDecimal amount, Throwable t) {
        log.error("Circuit breaker fallback: sign-transfer failed, cause={}", t.getMessage());
        return null;
    }
}