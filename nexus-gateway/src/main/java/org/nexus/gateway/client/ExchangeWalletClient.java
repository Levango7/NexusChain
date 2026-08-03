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

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP client for nexus-exchange-wallet service.
 * Handles transfer construction, signing, and broadcast via the wallet service.
 *
 * Exchange-wallet API:
 *   POST /ClientToTransferAccount?fromPubkey=&toPubkeyHash=&amount=&prikey=
 *   GET  /addressToPubkeyHash?address=
 *   GET  /verifyAddress?address=
 */
@Component
public class ExchangeWalletClient {

    private static final Logger log = LoggerFactory.getLogger(ExchangeWalletClient.class);

    private final RestTemplate restTemplate;
    private final GatewayConfig gatewayConfig;

    public ExchangeWalletClient(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Execute a NEX transfer via the exchange-wallet service.
     * The wallet service handles nonce management, tx construction, signing, and broadcast.
     *
     * @param fromPubkey   sender's public key (hex)
     * @param toPubkeyHash recipient's public key hash (hex)
     * @param amount       transfer amount in smallest unit
     * @param privateKey   sender's private key (hex)
     * @return transaction hash on success, null on failure
     */
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

    /**
     * Delegate on-chain settlement signing to exchange-wallet's {@code /api/v1/transfers/sign}
     * endpoint. Unlike {@link #transfer(String, String, BigDecimal, String)}, this method does
     * NOT pass a private key: exchange-wallet signs with its own server-side keystore, so the
     * gateway never handles the platform private key.
     *
     * @param fromPubkey   platform (hot-wallet) public key (hex)
     * @param toPubkeyHash recipient's public key hash (hex)
     * @param amount       transfer amount in smallest unit
     * @return transaction hash on success, null on failure
     */
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

    /**
     * Convert a NEX address to its public key hash.
     *
     * @param address NEX address (e.g. "1CRXnUJx9Tq4ZpNkkueeKFxCbYg1E4uTCt")
     * @return pubkey hash hex string, or null on failure
     */
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
        } catch (Exception e) {
            log.warn("Failed to convert address to pubkeyHash: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Verify whether a NEX address is valid.
     *
     * @param address NEX address
     * @return true if valid
     */
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
        } catch (Exception e) {
            log.warn("Failed to verify address: {}", e.getMessage());
            return false;
        }
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

    private String addressToPubkeyHashFallback(String address, Throwable t) {
        log.warn("Circuit breaker fallback: addressToPubkeyHash, cause={}", t.getMessage());
        return null;
    }

    private boolean verifyAddressFallback(String address, Throwable t) {
        log.warn("Circuit breaker fallback: verifyAddress, cause={}", t.getMessage());
        return false;
    }
}
