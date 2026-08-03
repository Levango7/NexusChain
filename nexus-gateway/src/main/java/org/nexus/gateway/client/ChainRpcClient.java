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
 * HTTP client for NexusChain Core node RPC endpoints.
 * Wraps the node's form-encoded REST API (port 19585 by default).
 */
@Component
public class ChainRpcClient {

    private static final Logger log = LoggerFactory.getLogger(ChainRpcClient.class);

    private final RestTemplate restTemplate;
    private final GatewayConfig gatewayConfig;

    public ChainRpcClient(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Query transaction confirmation status from the core node.
     * RPC: GET /transactionConfirmed?txHash={hash}
     *
     * @param txHash on-chain transaction hash
     * @return true if the transaction has sufficient confirmations (statusCode 2100)
     */
    @CircuitBreaker(name = "chainNode", fallbackMethod = "isTransactionConfirmedFallback")
    @Retry(name = "chainNode")
    public boolean isTransactionConfirmed(String txHash) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(gatewayConfig.getChain().getRpcUrl())
                    .pathSegment("transactionConfirmed")
                    .queryParam("txHash", txHash)
                    .toUriString();
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return false;

            // Core returns APIResult {code, message, data}. For /transactionConfirmed the
            // data field is 2000 (CONFIRMED) or 2100 (NOT_CONFIRMED).
            Object data = resp.getBody().get("data");
            if (data instanceof Number) {
                return ((Number) data).intValue() == 2000;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to query chain confirmation for txHash={}: {}", txHash, e.getMessage());
            // Dev-mode fallback: if chain node is unreachable and skip-confirmation is enabled,
            // treat any well-formed txHash as confirmed to allow local testing.
            if (gatewayConfig.getChain().isSkipConfirmation()) {
                log.info("DEV MODE: chain node unreachable, skip-confirmation=true, accepting txHash={}", txHash);
                return txHash != null && txHash.length() >= 16;
            }
            return false;
        }
    }

    /**
     * Get the current block height from the core node.
     * RPC: GET /height
     *
     * @return current block height, or -1 on failure
     */
    @CircuitBreaker(name = "chainNode", fallbackMethod = "getBlockHeightFallback")
    public long getBlockHeight() {
        try {
            String url = gatewayConfig.getChain().getRpcUrl() + "/height";
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return -1;
            Object data = resp.getBody().get("data");
            if (data instanceof Number) {
                return ((Number) data).longValue();
            }
            return -1;
        } catch (Exception e) {
            log.warn("Failed to get block height: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Get the nonce for an address from the core node.
     * RPC: POST /sendNonce (form: pubkeyhash={hash})
     *
     * @param pubkeyHash public key hash of the account
     * @return nonce value, or -1 on failure
     */
    public long getNonce(String pubkeyHash) {
        try {
            String url = gatewayConfig.getChain().getRpcUrl() + "/sendNonce";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("pubkeyhash", pubkeyHash);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);

            if (resp.getBody() == null) return -1;
            // Core /sendNonce returns APIResult {code, message, data} where data is the
            // nonce, or {nonce: <value>}. Tolerate both shapes.
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
            log.warn("Failed to get nonce for pubkeyHash={}: {}", pubkeyHash, e.getMessage());
            return -1;
        }
    }

    /**
     * Broadcast a signed transaction to the network.
     * RPC: POST /sendTransaction (form: traninfo={hex})
     *
     * @param signedTxHex signed transaction hex string
     * @return true if broadcast was accepted
     */
    @CircuitBreaker(name = "chainNode", fallbackMethod = "broadcastTransactionFallback")
    @Retry(name = "chainNode")
    public boolean broadcastTransaction(String signedTxHex) {
        try {
            String url = gatewayConfig.getChain().getRpcUrl() + "/sendTransaction";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("traninfo", signedTxHex);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, request, Map.class);

            if (resp.getBody() == null) return false;
            // Core returns APIResult {code, message, data}. Success code for a verified
            // and accepted transfer is 2000.
            Object code = resp.getBody().get("code");
            if (code instanceof Number) {
                return ((Number) code).intValue() == 2000;
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to broadcast transaction: {}", e.getMessage());
            return false;
        }
    }

    // --- Circuit breaker fallbacks ---

    private boolean isTransactionConfirmedFallback(String txHash, Throwable t) {
        log.warn("Circuit breaker fallback: isTransactionConfirmed, cause={}", t.getMessage());
        if (gatewayConfig.getChain().isSkipConfirmation()) {
            return txHash != null && txHash.length() >= 16;
        }
        return false;
    }

    private long getBlockHeightFallback(Throwable t) {
        log.warn("Circuit breaker fallback: getBlockHeight, cause={}", t.getMessage());
        return -1;
    }

    private boolean broadcastTransactionFallback(String signedTxHex, Throwable t) {
        log.warn("Circuit breaker fallback: broadcastTransaction, cause={}", t.getMessage());
        return false;
    }
}
