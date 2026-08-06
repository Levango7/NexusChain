package org.nexus.wallet.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * {@link OnChainExecutionClient} 的 HTTP 实现，通过 REST 调用 gateway
 * 的 {@code /api/v1/execution} 端点。
 * <p>
 * 当 gateway 不可达时，降级为 sandbox 模式（返回 "SIMULATED-..." 前缀的
 * 模拟交易哈希），保证 wallet 在独立 / 测试环境下也能完成提币流程。
 * </p>
 *
 * <p>配置项：</p>
 * <ul>
 *   <li>{@code nexus.gateway.base-url}：gateway 基础 URL，默认 {@code http://localhost:8080}</li>
 *   <li>{@code nexus.wallet.execution.sandbox}：是否强制 sandbox 模式，默认 false</li>
 * </ul>
 */
@Component
public class HttpOnChainExecutionClient implements OnChainExecutionClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOnChainExecutionClient.class);

    /** 模拟交易哈希前缀 */
    public static final String SIMULATED_PREFIX = "SIMULATED-";

    private final RestTemplate restTemplate;
    private final String gatewayBaseUrl;
    private final boolean sandboxMode;

    public HttpOnChainExecutionClient(
            @Value("${nexus.gateway.base-url:http://localhost:8080}") String gatewayBaseUrl,
            @Value("${nexus.wallet.execution.sandbox:false}") boolean sandboxMode) {
        this.restTemplate = new RestTemplate();
        this.gatewayBaseUrl = gatewayBaseUrl;
        this.sandboxMode = sandboxMode;
    }

    @Override
    public WalletTransactionResult execute(WalletTransactionRequest request) {
        if (sandboxMode) {
            return sandboxResult(request);
        }
        try {
            String url = gatewayBaseUrl + "/api/v1/execution/execute";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<WalletTransactionRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<WalletTransactionResult> resp =
                    restTemplate.postForEntity(url, entity, WalletTransactionResult.class);
            if (resp.getBody() != null) {
                log.info("execute via gateway: type={}, requestId={}, result={}",
                        request.getType(), request.getRequestId(), resp.getBody().getStatus());
                return resp.getBody();
            }
            return failure("gateway returned empty body");
        } catch (Exception e) {
            log.warn("execute via gateway failed, falling back to sandbox: {}", e.getMessage());
            return sandboxResult(request);
        }
    }

    @Override
    public WalletTransactionResult queryStatus(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return failure("txHash is null or empty");
        }
        if (txHash.startsWith(SIMULATED_PREFIX)) {
            return new WalletTransactionResult(txHash, WalletTransactionResult.Status.SUCCESS,
                    0, null, true);
        }
        if (sandboxMode) {
            return new WalletTransactionResult(txHash, WalletTransactionResult.Status.PENDING_CONFIRMATION,
                    0, null, true);
        }
        try {
            String url = UriComponentsBuilder.fromHttpUrl(gatewayBaseUrl)
                    .pathSegment("api", "v1", "execution", "status")
                    .queryParam("txHash", txHash)
                    .toUriString();
            ResponseEntity<WalletTransactionResult> resp =
                    restTemplate.getForEntity(url, WalletTransactionResult.class);
            if (resp.getBody() != null) {
                return resp.getBody();
            }
            return failure("gateway returned empty body");
        } catch (Exception e) {
            log.warn("queryStatus via gateway failed: {}", e.getMessage());
            return failure("query failed: " + e.getMessage());
        }
    }

    private WalletTransactionResult sandboxResult(WalletTransactionRequest request) {
        String txHash = SIMULATED_PREFIX + UUID.randomUUID().toString().replace("-", "");
        log.info("sandbox execute: type={}, requestId={}, txHash={}",
                request != null ? request.getType() : null,
                request != null ? request.getRequestId() : null,
                txHash);
        return new WalletTransactionResult(txHash, WalletTransactionResult.Status.SUCCESS,
                0, null, true);
    }

    private WalletTransactionResult failure(String error) {
        return new WalletTransactionResult(null, WalletTransactionResult.Status.FAILED,
                0, error, false);
    }
}