package org.nexus.gateway.orchestration.connectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.settlement.FinalityPolicy;
import org.nexus.gateway.orchestration.settlement.PspFinalityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Alipay Payment Connector - integrates with Alipay's OpenAPI.
 * Supports: 当面付 (Face-to-face / precreate) and 网页支付 (web payment).
 * Requires: nexus.connectors.alipay.app-id + merchant-private-key + alipay-public-key in config.
 * In sandbox mode without a real key, operates in dry-run (simulates success).
 *
 * <p>支付宝 API 使用统一网关 {@code https://openapi-sandbox.dl.alipaydev.com/gateway.do}，
 * 通过 method 参数区分不同接口（alipay.trade.precreate / alipay.trade.query / alipay.trade.refund）。
 * 认证方式为 RSA2 签名，请求参数中包含 app_id、sign、sign_type=RSA2。</p>
 *
 * <p>签名框架（Wave 7-A2）：集成 RSA2 签名生成，每次 API 调用自动添加 sign 参数。
 * sandbox=true 时保持 dry-run 模拟响应，响应格式与真实 API 一致。</p>
 */
@Component
public class AlipayConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(AlipayConnector.class);
    private static final String DEFAULT_ALIPAY_API_BASE = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    @Value("${nexus.connectors.alipay.app-id:}")
    private String appId;

    @Value("${nexus.connectors.alipay.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${nexus.connectors.alipay.alipay-public-key:}")
    private String alipayPublicKey;

    @Value("${nexus.connectors.alipay.api-base-url:https://openapi-sandbox.dl.alipaydev.com/gateway.do}")
    private String apiBaseUrl = DEFAULT_ALIPAY_API_BASE;

    @Value("${nexus.connectors.alipay.enabled:false}")
    private boolean enabled;

    /** sandbox=true 时保持 dry-run 模拟响应；false 时发起真实 API 调用 */
    @Value("${nexus.connectors.alipay.sandbox:true}")
    private boolean sandbox;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();

    @Autowired
    public AlipayConnector(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /** 测试用兼容构造器。 */
    public AlipayConnector() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getId() { return "alipay"; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return "Alipay (当面付/网页支付)"; }

    @Override
    public boolean isActive() { return enabled; }

    /**
     * 判断是否处于 dry-run 模式：sandbox=true 或 merchantPrivateKey 为空。
     */
    private boolean isDryRun() {
        return sandbox || merchantPrivateKey == null || merchantPrivateKey.isBlank();
    }

    /**
     * 构建支付宝请求参数（含 RSA2 签名）。
     *
     * <p>支付宝统一网关请求格式：</p>
     * <ol>
     *   <li>组装业务参数（app_id、method、sign_type、timestamp、nonce、biz_content 等）</li>
     *   <li>对所有参数按 key 排序拼接，用商户私钥做 RSA2 签名</li>
     *   <li>将 sign 和 sign_type 附加到请求参数中</li>
     * </ol>
     *
     * @param method      API 方法名（如 alipay.trade.precreate）
     * @param bizContent  业务参数 JSON 字符串
     * @return 完整的表单参数字符串（含签名）
     */
    private String buildSignedRequest(String method, String bizContent) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", appId);
        params.put("method", method);
        params.put("sign_type", "RSA2");
        params.put("timestamp", java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("nonce", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        params.put("biz_content", bizContent);

        // 生成 RSA2 签名
        if (!isDryRun()) {
            String sign = AlipaySignatureUtil.generateSignature(params, merchantPrivateKey);
            params.put("sign", sign);
        }

        // 拼接为表单参数字符串
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        if (isDryRun()) {
            // Dry-run 模式：模拟当面付 precreate 成功响应
            String id = "alipay_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[Alipay DRY-RUN] 当面付模拟成功: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            // 模拟真实 API 响应格式：返回 qr_code（扫码链接）
            ConnectorPaymentResult result = ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
            result.setRedirectUrl("https://qr.alipay.com/dryrun_" + id);
            return result;
        }

        try {
            // 当面付 API：alipay.trade.precreate
            String description = request.getDescription() != null ? request.getDescription() : "NexusChain Payment";
            Map<String, Object> bizContentMap = new LinkedHashMap<>();
            bizContentMap.put("out_trade_no", request.getPaymentId());
            // 支付宝 total_amount 要求元为单位的小数字符串，如 "0.01"
            bizContentMap.put("total_amount", formatAmount(request.getAmount()));
            bizContentMap.put("subject", description);
            String bizContent;
            try {
                bizContent = objectMapper.writeValueAsString(bizContentMap);
            } catch (JsonProcessingException e) {
                log.error("[Alipay] bizContent 序列化失败: {}", e.getMessage());
                return ConnectorPaymentResult.fail("bizContent序列化失败");
            }

            String body = buildSignedRequest("alipay.trade.precreate", bizContent);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (resp.getBody() != null) {
                String qrCode = String.valueOf(resp.getBody().getOrDefault("qr_code", ""));
                String tradeNo = String.valueOf(resp.getBody().getOrDefault("trade_no", "unknown"));
                String tradeStatus = String.valueOf(resp.getBody().getOrDefault("trade_status", "WAIT_BUYER_PAY"));
                PaymentStatus mapped = mapAlipayStatus(tradeStatus);
                localState.put(tradeNo, mapped);

                ConnectorPaymentResult result = ConnectorPaymentResult.ok(tradeNo, mapped);
                if (!qrCode.isEmpty()) {
                    result.setRedirectUrl(qrCode);
                }
                log.info("[Alipay] 当面付下单成功: tradeNo={} status={}", tradeNo, tradeStatus);
                return result;
            }
            return ConnectorPaymentResult.fail("Alipay returned empty response");
        } catch (RuntimeException e) {
            log.error("[Alipay] createPayment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("Alipay error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        if (isDryRun()) {
            return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }

        try {
            // 查询订单 API：alipay.trade.query
            Map<String, Object> bizContentMap = new LinkedHashMap<>();
            bizContentMap.put("out_trade_no", connectorPaymentId);
            String bizContent;
            try {
                bizContent = objectMapper.writeValueAsString(bizContentMap);
            } catch (JsonProcessingException e) {
                log.error("[Alipay] bizContent 序列化失败: {}", e.getMessage());
                return PaymentStatus.FAILED;
            }

            String body = buildSignedRequest("alipay.trade.query", bizContent);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (resp.getBody() != null) {
                String tradeStatus = String.valueOf(resp.getBody().getOrDefault("trade_status", ""));
                PaymentStatus mapped = mapAlipayStatus(tradeStatus);
                localState.put(connectorPaymentId, mapped);
                cleanupTerminalState(connectorPaymentId, mapped);
                log.info("[Alipay] 查询订单: out_trade_no={} trade_status={} -> {}", connectorPaymentId, tradeStatus, mapped);
                return mapped;
            }
        } catch (RuntimeException e) {
            log.warn("[Alipay] query failed for {}: {}", connectorPaymentId, e.getMessage());
        }
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    /**
     * 关闭订单 — 支付宝 API：alipay.trade.close
     *
     * <p>用于关闭未支付的订单。订单关闭后不可再次支付。</p>
     *
     * @param connectorPaymentId 商户订单号（out_trade_no）
     * @return true 关闭成功，false 关闭失败
     */
    public boolean closePayment(String connectorPaymentId) {
        if (isDryRun()) {
            localState.put(connectorPaymentId, PaymentStatus.CANCELLED);
            log.info("[Alipay DRY-RUN] 关闭订单模拟成功: {}", connectorPaymentId);
            return true;
        }
        try {
            Map<String, Object> bizContentMap = new LinkedHashMap<>();
            bizContentMap.put("out_trade_no", connectorPaymentId);
            String bizContent;
            try {
                bizContent = objectMapper.writeValueAsString(bizContentMap);
            } catch (JsonProcessingException e) {
                log.error("[Alipay] bizContent 序列化失败: {}", e.getMessage());
                return false;
            }

            String body = buildSignedRequest("alipay.trade.close", bizContent);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(apiBaseUrl, entity, Map.class);
            localState.put(connectorPaymentId, PaymentStatus.CANCELLED);
            log.info("[Alipay] 关闭订单成功: out_trade_no={}", connectorPaymentId);
            return true;
        } catch (RuntimeException e) {
            log.error("[Alipay] closePayment failed for {}: {}", connectorPaymentId, e.getMessage());
            return false;
        }
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (isDryRun()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("alipay_refund_" + connectorPaymentId);
        }

        try {
            // 退款 API：alipay.trade.refund
            Map<String, Object> bizContentMap = new LinkedHashMap<>();
            bizContentMap.put("out_trade_no", connectorPaymentId);
            bizContentMap.put("refund_amount", formatAmount(amount));
            String bizContent;
            try {
                bizContent = objectMapper.writeValueAsString(bizContentMap);
            } catch (JsonProcessingException e) {
                log.error("[Alipay] bizContent 序列化失败: {}", e.getMessage());
                return ConnectorRefundResult.fail("bizContent序列化失败");
            }

            String body = buildSignedRequest("alipay.trade.refund", bizContent);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (resp.getBody() != null) {
                String fundChange = String.valueOf(resp.getBody().getOrDefault("fund_change", "Y"));
                if ("Y".equals(fundChange)) {
                    localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
                    log.info("[Alipay] 退款成功: out_trade_no={}", connectorPaymentId);
                    return ConnectorRefundResult.ok("alipay_refund_" + connectorPaymentId);
                }
                return ConnectorRefundResult.fail("Alipay refund: fund_change=N");
            }
            return ConnectorRefundResult.fail("Alipay refund returned empty response");
        } catch (RuntimeException e) {
            return ConnectorRefundResult.fail("Alipay refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        if (isDryRun()) return ConnectorHealth.up(getId(), 0);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> bizContentMap = new LinkedHashMap<>();
            bizContentMap.put("out_trade_no", "health_check_dummy");
            String bizContent;
            try {
                bizContent = objectMapper.writeValueAsString(bizContentMap);
            } catch (JsonProcessingException e) {
                log.error("[Alipay] bizContent 序列化失败: {}", e.getMessage());
                return ConnectorHealth.down(getId(), "bizContent序列化失败");
            }

            String body = buildSignedRequest("alipay.trade.query", bizContent);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(apiBaseUrl, entity, Map.class);
            return ConnectorHealth.up(getId(), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            return ConnectorHealth.down(getId(), e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of("CNY"); }

    @Override
    public FinalityPolicy getFinalityPolicy() {
        return new PspFinalityPolicy();
    }

    @Override
    public int feeBasisPoints() { return 38; } // 支付宝费率约 0.38%

    /**
     * 将金额（分）转换为支付宝要求的元格式字符串。
     * 支付宝 total_amount 必须为字符串，如 "0.01"。
     *
     * @param amountInCents 金额（分）
     * @return 元格式字符串，如 "0.01"
     */
    private String formatAmount(long amountInCents) {
        return String.format("%.2f", amountInCents / 100.0);
    }

    /**
     * 将支付宝交易状态映射到 PaymentStatus 枚举。
     * 采用 fail-closed 策略：未知状态一律映射为 FAILED。
     */
    private PaymentStatus mapAlipayStatus(String alipayStatus) {
        return switch (alipayStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> PaymentStatus.SUCCEEDED;
            case "WAIT_BUYER_PAY" -> PaymentStatus.PROCESSING;
            case "TRADE_CLOSED" -> PaymentStatus.CANCELLED;
            case "TRADE_REFUND" -> PaymentStatus.REFUNDED;
            default -> PaymentStatus.FAILED; // fail-closed
        };
    }

    /**
     * P0-5：URL 编码辅助方法，确保表单参数值经过编码，防止注入。
     */
    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return URLEncoder.encode(value);
        }
    }

    /**
     * P1-3：支付进入终态后清理 localState 条目，防止内存泄漏。
     *
     * @param connectorPaymentId 连接器支付 ID
     * @param status             当前支付状态
     */
    private void cleanupTerminalState(String connectorPaymentId, PaymentStatus status) {
        if (status == PaymentStatus.SUCCEEDED
                || status == PaymentStatus.FAILED
                || status == PaymentStatus.REFUNDED
                || status == PaymentStatus.CANCELLED) {
            localState.remove(connectorPaymentId);
        }
    }
}
