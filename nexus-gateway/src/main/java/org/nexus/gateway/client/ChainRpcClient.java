package org.nexus.gateway.client;

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

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP client for NexusChain Core node RPC endpoints.
 * Wraps the node's form-encoded REST API (port 19585 by default).
 *
 * <p>Phase 1 任务 #55：Resilience4j {@code @CircuitBreaker}/{@code @Retry} 注解保留
 * （管理对链节点直接 HTTP 调用的熔断/重试，与 Sentinel 共存：Sentinel 管理 Feign 层
 * 对 signing/wallet-service 的调用）。</p>
 *
 * <p>性能优化（任务 #310）：注入共享的连接池化 RestTemplate，
 * 替代 {@code new RestTemplate()} 创建的无连接池实例，复用 TCP/TLS 连接。</p>
 */
@Component
public class ChainRpcClient {

    private static final Logger log = LoggerFactory.getLogger(ChainRpcClient.class);

    private final RestTemplate restTemplate;
    private final GatewayConfig gatewayConfig;

    @Autowired
    public ChainRpcClient(GatewayConfig gatewayConfig, RestTemplate restTemplate) {
        this.gatewayConfig = gatewayConfig;
        this.restTemplate = restTemplate;
    }

    /** 测试用兼容构造器：保留无连接池 RestTemplate，便于单元测试注入 mock。 */
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
            String url = UriComponentsBuilder.fromUriString(gatewayConfig.getChain().getRpcUrl())
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
        } catch (RuntimeException e) {
            log.warn("Failed to query chain confirmation for txHash={}: {}", txHash, e.getMessage());
            // Dev-mode fallback: if chain node is unreachable and skip-confirmation is enabled,
            // treat any well-formed txHash as confirmed to allow local testing.
            // B-19 修复：即使 skip-confirmation 也必须验证 txHash 格式（非空、长度合理），
            // 并记录 warn 日志明确说明跳过了链上确认，避免接受任意 txHash。
            if (gatewayConfig.getChain().isSkipConfirmation()) {
                if (isValidTxHashFormat(txHash)) {
                    log.warn("SKIP-CONFIRMATION: chain node unreachable, accepting txHash={} without on-chain verification (dev mode only)",
                            txHash);
                    return true;
                }
                log.warn("SKIP-CONFIRMATION: txHash={} rejected due to invalid format (non-empty, length >= 16 required)", txHash);
                return false;
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
        } catch (RuntimeException e) {
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
        } catch (RuntimeException e) {
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
        } catch (RuntimeException e) {
            log.warn("Failed to broadcast transaction: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 查询链上交易的完整详情（P0-5 修复，v2.27.0）。
     *
     * <p>调用 core: GET /rpc/v1/transaction/{txHash}，返回交易金额、收款人、发送方等完整信息。
     * 用于支付确认时校验交易-订单绑定（金额一致 + 收款人为商户结算地址）。
     * 链节点不支持此端点或交易不存在时返回 null，调用方应做 null 检查并降级处理。</p>
     *
     * @param txHash 链上交易哈希
     * @return 交易详情；链不可达或交易未找到返回 null
     */
    @SuppressWarnings("unchecked")
    public OnChainTransaction getTransaction(String txHash) {
        if (txHash == null || txHash.isEmpty()) return null;
        try {
            String url = gatewayConfig.getChain().getRpcUrl() + "/rpc/v1/transaction/" + txHash;
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return null;
            Object data = resp.getBody().get("data");
            if (!(data instanceof Map)) return null;
            Map<String, Object> d = (Map<String, Object>) data;

            BigDecimal amount = null;
            Object amountObj = d.get("amount");
            if (amountObj instanceof Number) {
                amount = new BigDecimal(amountObj.toString());
            } else if (amountObj instanceof String) {
                amount = new BigDecimal((String) amountObj);
            }

            String tokenSymbol = d.get("token_symbol") != null ? String.valueOf(d.get("token_symbol")) : null;
            String sender = d.get("sender") != null ? String.valueOf(d.get("sender")) : null;
            String recipient = d.get("recipient") != null ? String.valueOf(d.get("recipient")) : null;
            Boolean confirmed = d.get("confirmed") instanceof Boolean ? (Boolean) d.get("confirmed") : null;
            Long blockHeight = d.get("block_height") instanceof Number ? ((Number) d.get("block_height")).longValue() : null;

            return new OnChainTransaction(txHash, amount, tokenSymbol, sender, recipient, confirmed, blockHeight);
        } catch (RuntimeException e) {
            log.warn("Failed to get transaction details for txHash={}: {}", txHash, e.getMessage());
            return null;
        }
    }

    // --- Circuit breaker fallbacks ---

    /**
     * 查询指定 epoch 的最终性进度（BFT 质押权重驱动，NexFinality）。
     * 调用 core: GET /rpc/v1/finality/epoch/{epoch}
     *
     * @param epoch 共识 epoch 编号
     * @return 返回 {finality_status, voted_weight, total_weight, progress_percent}；
     *         链不可达或最终性层未启用返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEpochFinality(long epoch) {
        try {
            String url = gatewayConfig.getChain().getRpcUrl() + "/rpc/v1/finality/epoch/" + epoch;
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return null;
            Object data = resp.getBody().get("data");
            if (data instanceof Map) {
                Map<String, Object> d = (Map<String, Object>) data;
                if ("NOT_ACTIVE".equals(String.valueOf(d.get("finality_status")))) {
                    return null;  // 最终性层未启用 → 上层降级 confirmations 驱动
                }
                return d;
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Failed to get epoch finality for epoch={}: {}", epoch, e.getMessage());
            return null;
        }
    }

    /**
     * 查询链上交易的详细确认状态（含确认数与高度），供最终性推导使用。
     * 调用 core: GET /rpc/v1/transaction/{txHash}/status
     *
     * @param txHash 链上交易哈希
     * @return 返回 {status, confirmations, block_height}；链不可达或交易未找到返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTransactionStatus(String txHash) {
        if (txHash == null || txHash.isEmpty()) return null;
        try {
            String url = gatewayConfig.getChain().getRpcUrl() + "/rpc/v1/transaction/" + txHash + "/status";
            ResponseEntity<Map> resp = restTemplate.getForEntity(url, Map.class);
            if (resp.getBody() == null) return null;
            Object data = resp.getBody().get("data");
            if (data instanceof Map) {
                return (Map<String, Object>) data;
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("Failed to get transaction status for txHash={}: {}", txHash, e.getMessage());
            return null;
        }
    }

    private boolean isTransactionConfirmedFallback(String txHash, Throwable t) {
        log.warn("Circuit breaker fallback: isTransactionConfirmed, cause={}", t.getMessage());
        // B-19 修复：fallback 路径同样需要验证 txHash 格式并记录 warn 日志
        if (gatewayConfig.getChain().isSkipConfirmation()) {
            if (isValidTxHashFormat(txHash)) {
                log.warn("SKIP-CONFIRMATION fallback: accepting txHash={} without on-chain verification (dev mode only)",
                        txHash);
                return true;
            }
            log.warn("SKIP-CONFIRMATION fallback: txHash={} rejected due to invalid format", txHash);
            return false;
        }
        return false;
    }

    /**
     * 校验交易哈希格式（B-19 修复）。
     *
     * <p>skip-confirmation 模式下仍需验证 txHash 格式，防止接受任意字符串。
     * 格式要求：非空、长度 >= 16（链上交易哈希通常为 64 hex 字符，16 为保守下限）。</p>
     *
     * @param txHash 待校验的交易哈希
     * @return 格式合法返回 true
     */
    private static boolean isValidTxHashFormat(String txHash) {
        return txHash != null && txHash.length() >= 16;
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
