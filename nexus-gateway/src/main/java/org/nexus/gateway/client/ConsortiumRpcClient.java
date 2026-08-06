package org.nexus.gateway.client;

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
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * HTTP client for NexusChain Consortium node RPC endpoints.
 *
 * <p>Wraps the consortium node's REST API. The consortium is a PoA permissioned
 * sidechain (see {@code nexus-consortium}) that listens on port 8080 by default
 * (see {@code nexus-consortium/consortium/src/main/resources/application.yml}:
 * {@code server.port: '8080'}).</p>
 *
 * <p>The consortium node currently exposes a limited REST surface
 * ({@code /hello}, {@code /account/{address}}, {@code /config}, {@code /peers})
 * via {@code EntryController}; transaction broadcast and confirmation happen
 * over the P2P gRPC channel. This client mirrors {@link ChainRpcClient}'s
 * form-encoded RPC pattern so that, once the consortium REST surface is
 * extended (or a bridge proxy exposes the same {@code /sendTransaction},
 * {@code /transactionConfirmed}, {@code /height} endpoints as core), the
 * gateway can drive the consortium chain uniformly. When the consortium node
 * is unreachable and {@code skip-confirmation} is enabled, the client falls
 * back to a dev-mode behaviour that allows local testing.</p>
 *
 * <p>Phase 1 任务 #55：Resilience4j {@code @CircuitBreaker}/{@code @Retry} 注解保留
 * （管理对链节点直接 HTTP 调用的熔断/重试，与 Sentinel 共存）。</p>
 */
@Component
public class ConsortiumRpcClient {

    private static final Logger log = LoggerFactory.getLogger(ConsortiumRpcClient.class);

    private final RestTemplate restTemplate;
    private final GatewayConfig gatewayConfig;

    public ConsortiumRpcClient(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Query transaction confirmation status from the consortium node.
     * RPC: GET /transactionConfirmed?txHash={hash}
     *
     * @param txHash on-chain transaction hash
     * @return true if the transaction has sufficient confirmations (statusCode 2000)
     */

    @CircuitBreaker(name = "consortiumNode", fallbackMethod = "isTransactionConfirmedFallback")
    @Retry(name = "consortiumNode")
    public boolean isTransactionConfirmed(String txHash) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(gatewayConfig.getConsortium().getRpcUrl())
                    .pathSegment("transactionConfirmed")
                    .queryParam("txHash", txHash)
                    .toUriString();
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return false;

            // Consortium returns APIResult {code, message, data}. For
            // /transactionConfirmed the data field is 2000 (CONFIRMED) or
            // 2100 (NOT_CONFIRMED), mirroring core's contract.
            Object data = resp.getBody().get("data");
            if (data instanceof Number) {
                return ((Number) data).intValue() == 2000;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to query consortium confirmation for txHash={}: {}", txHash, e.getMessage());
            // Dev-mode fallback: if consortium node is unreachable and
            // skip-confirmation is enabled, treat any well-formed txHash as
            // confirmed to allow local testing.
            if (gatewayConfig.getConsortium().isSkipConfirmation()) {
                log.info("DEV MODE: consortium node unreachable, skip-confirmation=true, accepting txHash={}", txHash);
                return txHash != null && txHash.length() >= 16;
            }
            return false;
        }
    }

    /**
     * Get the current block height from the consortium node.
     * RPC: GET /height
     *
     * @return current block height, or -1 on failure
     */

    @CircuitBreaker(name = "consortiumNode", fallbackMethod = "getBlockHeightFallback")
    public long getBlockHeight() {
        try {
            String url = gatewayConfig.getConsortium().getRpcUrl() + "/height";
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return -1;
            Object data = resp.getBody().get("data");
            if (data instanceof Number) {
                return ((Number) data).longValue();
            }
            return -1;
        } catch (Exception e) {
            log.warn("Failed to get consortium block height: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Get the nonce for an address from the consortium node.
     * RPC: POST /sendNonce (form: pubkeyhash={hash})
     *
     * @param pubkeyHash public key hash of the account
     * @return nonce value, or -1 on failure
     */
    public long getNonce(String pubkeyHash) {
        try {
            String url = gatewayConfig.getConsortium().getRpcUrl() + "/sendNonce";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("pubkeyhash", pubkeyHash);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);

            if (resp.getBody() == null) return -1;
            // Consortium /sendNonce returns APIResult {code, message, data} where
            // data is the nonce, or {nonce: <value>}. Tolerate both shapes.
            Object data = resp.getBody().get("data");
            if (data instanceof Number) {
                return ((Number) data).longValue();
            }
            Object nonce = resp.getBody().get("nonce");
            if (nonce instanceof Number) {
                return ((Number) nonce).longValue();
            }
            return -1;
        } catch (Exception e) {
            log.warn("Failed to get consortium nonce for pubkeyHash={}: {}", pubkeyHash, e.getMessage());
            return -1;
        }
    }

    /**
     * Broadcast a signed transaction to the consortium network.
     * RPC: POST /sendTransaction (form: traninfo={hex})
     *
     * @param signedTxHex signed transaction hex string
     * @return true if broadcast was accepted
     */

    @CircuitBreaker(name = "consortiumNode", fallbackMethod = "broadcastTransactionFallback")
    @Retry(name = "consortiumNode")
    public boolean broadcastTransaction(String signedTxHex) {
        try {
            String url = gatewayConfig.getConsortium().getRpcUrl() + "/sendTransaction";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("traninfo", signedTxHex);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);

            if (resp.getBody() == null) return false;
            // Consortium returns APIResult {code, message, data}. Success code
            // for a verified and accepted transfer is 2000.
            Object code = resp.getBody().get("code");
            if (code instanceof Number) {
                return ((Number) code).intValue() == 2000;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to broadcast consortium transaction: {}", e.getMessage());
            return false;
        }
    }

    // --- Circuit breaker fallbacks ---

    private boolean isTransactionConfirmedFallback(String txHash, Throwable t) {
        log.warn("Circuit breaker fallback: consortium isTransactionConfirmed, cause={}", t.getMessage());
        if (gatewayConfig.getConsortium().isSkipConfirmation()) {
            return txHash != null && txHash.length() >= 16;
        }
        return false;
    }

    private long getBlockHeightFallback(Throwable t) {
        log.warn("Circuit breaker fallback: consortium getBlockHeight, cause={}", t.getMessage());
        return -1;
    }

    private boolean broadcastTransactionFallback(String signedTxHex, Throwable t) {
        log.warn("Circuit breaker fallback: consortium broadcastTransaction, cause={}", t.getMessage());
        return false;
    }
}