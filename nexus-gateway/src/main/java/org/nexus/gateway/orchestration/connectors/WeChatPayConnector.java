package org.nexus.gateway.orchestration.connectors;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WeChat Pay Connector - integrates with WeChat Pay's Native (扫码) and JSAPI APIs.
 * Requires: nexus.connectors.wechat.api-key + app-id + mch-id in config.
 * In sandbox mode without a real key, operates in dry-run (simulates success).
 *
 * <p>支持 Native（扫码支付）和 JSAPI 两种支付方式，默认使用 Native。</p>
 *
 * <p>签名框架（Wave 7-A2）：集成 V3 HMAC-SHA256 签名，每次 API 调用自动添加
 * Authorization 头。sandbox=true 时保持 dry-run 模拟响应，响应格式与真实 API 一致。</p>
 *
 * <p>性能优化（任务 #310）：注入共享的连接池化 RestTemplate。</p>
 */
@Component
public class WeChatPayConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayConnector.class);
    private static final String DEFAULT_WECHAT_API_BASE = "https://api.mch.weixin.qq.com";

    @Value("${nexus.connectors.wechat.api-key:}")
    private String apiKey;

    @Value("${nexus.connectors.wechat.app-id:}")
    private String appId;

    @Value("${nexus.connectors.wechat.mch-id:}")
    private String mchId;

    @Value("${nexus.connectors.wechat.enabled:false}")
    private boolean enabled;

    @Value("${nexus.connectors.wechat.api-base-url:https://api.mch.weixin.qq.com}")
    private String apiBase = DEFAULT_WECHAT_API_BASE;

    /** sandbox=true 时保持 dry-run 模拟响应；false 时发起真实 API 调用 */
    @Value("${nexus.connectors.wechat.sandbox:true}")
    private boolean sandbox;

    /** APIv3 密钥，用于 HMAC-SHA256 签名（与 api-key 可以相同或不同） */
    @Value("${nexus.connectors.wechat.api-v3-key:}")
    private String apiV3Key;

    /** 商户证书序列号，用于 Authorization 头 */
    @Value("${nexus.connectors.wechat.cert-serial-no:}")
    private String certSerialNo;

    private final RestTemplate restTemplate;
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();
    // P0-4：使用 ObjectMapper 安全构建 JSON，防止注入
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public WeChatPayConnector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 测试用兼容构造器。 */
    public WeChatPayConnector() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getId() { return "wechat"; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return "WeChat Pay (扫码/JSAPI)"; }

    @Override
    public boolean isActive() { return enabled; }

    /**
     * 判断是否处于 dry-run 模式：sandbox=true 或 apiV3Key 为空。
     */
    private boolean isDryRun() {
        return sandbox || apiV3Key == null || apiV3Key.isBlank();
    }

    /**
     * 构建微信支付 V3 Authorization 头。
     *
     * <p>格式：WECHATPAY2-SHA256-RSA2048 mchid="...",nonce_str="...",timestamp="...",
     * serial_no="...",signature="..."</p>
     *
     * <p>注意：V3 正式签名使用 RSA-SHA256（商户私钥签名），此处简化为 HMAC-SHA256
     * （使用 APIv3 密钥），在获得真实商户证书后切换即可。</p>
     */
    private String buildAuthorization(String method, String url, String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String signKey = (apiV3Key != null && !apiV3Key.isBlank()) ? apiV3Key : apiKey;
        String signature = WeChatPaySignatureUtil.generateSignature(
                method, url, timestamp, nonceStr, body, signKey);

        return String.format(
                "WECHATPAY2-SHA256-RSA2048 mchid=\"%s\",nonce_str=\"%s\",timestamp=\"%s\",serial_no=\"%s\",signature=\"%s\"",
                mchId, nonceStr, timestamp,
                (certSerialNo != null && !certSerialNo.isBlank()) ? certSerialNo : "DUMMY_SERIAL",
                signature);
    }

    /**
     * 为 HTTP 请求添加微信支付 V3 签名头。
     */
    private HttpHeaders buildSignedHeaders(String method, String url, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        if (!isDryRun()) {
            headers.set("Authorization", buildAuthorization(method, url, body));
        }
        return headers;
    }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        if (isDryRun()) {
            // Dry-run 模式：模拟统一下单成功响应
            String id = "wechat_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[WeChat DRY-RUN] 统一下单模拟成功: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            // 模拟真实 API 响应格式：返回 code_url（扫码链接）
            ConnectorPaymentResult result = ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
            result.setRedirectUrl("weixin://wxpay/bizpayurl?pr=dryrun_" + id);
            return result;
        }

        try {
            // 统一下单 API：POST /v3/pay/transactions/native
            String apiPath = "/v3/pay/transactions/native";
            String description = request.getDescription() != null ? request.getDescription() : "";
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("appid", appId);
            requestBody.put("mchid", mchId);
            requestBody.put("out_trade_no", request.getPaymentId());
            requestBody.put("description", description);
            Map<String, Object> amount = new LinkedHashMap<>();
            amount.put("total", request.getAmount());
            amount.put("currency", "CNY");
            requestBody.put("amount", amount);

            String json = objectMapper.writeValueAsString(requestBody);
            HttpHeaders headers = buildSignedHeaders("POST", apiPath, json);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + apiPath, entity, Map.class);

            if (resp.getBody() != null) {
                String codeUrl = String.valueOf(resp.getBody().getOrDefault("code_url", ""));
                String prepayId = String.valueOf(resp.getBody().getOrDefault("prepay_id", ""));
                String connectorPaymentId = request.getPaymentId();
                PaymentStatus mapped = PaymentStatus.PROCESSING;
                localState.put(connectorPaymentId, mapped);
                log.info("[WeChat] 统一下单成功: out_trade_no={} code_url={}", connectorPaymentId, codeUrl);

                ConnectorPaymentResult result = ConnectorPaymentResult.ok(connectorPaymentId, mapped);
                if (!codeUrl.isEmpty()) {
                    result.setRedirectUrl(codeUrl);
                } else if (!prepayId.isEmpty()) {
                    result.setRedirectUrl(prepayId);
                }
                return result;
            }
            return ConnectorPaymentResult.fail("WeChat Pay returned empty response");
        } catch (Exception e) {
            log.error("[WeChat] createPayment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("WeChat Pay error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        if (isDryRun()) {
            return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }
        try {
            // 查询订单 API：GET /v3/pay/transactions/out-trade-no/{out_trade_no}
            String apiPath = "/v3/pay/transactions/out-trade-no/" + connectorPaymentId + "?mchid=" + mchId;
            HttpHeaders headers = buildSignedHeaders("GET", apiPath, "");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    apiBase + apiPath, HttpMethod.GET, entity, Map.class);

            if (resp.getBody() != null) {
                String tradeState = String.valueOf(resp.getBody().getOrDefault("trade_state", "UNKNOWN"));
                PaymentStatus mapped = mapWeChatStatus(tradeState);
                localState.put(connectorPaymentId, mapped);
                cleanupTerminalState(connectorPaymentId, mapped);
                log.info("[WeChat] 查询订单: out_trade_no={} trade_state={} -> {}", connectorPaymentId, tradeState, mapped);
                return mapped;
            }
        } catch (Exception e) {
            log.warn("[WeChat] queryPayment failed for {}: {}", connectorPaymentId, e.getMessage());
        }
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    /**
     * 关闭订单 — 微信支付 V3 API：POST /v3/pay/transactions/out-trade-no/{out_trade_no}/close
     *
     * <p>当订单未支付时，商户可调用此接口关闭订单。订单关闭后不可再次支付。</p>
     *
     * @param connectorPaymentId 商户订单号（out_trade_no）
     * @return true 关闭成功，false 关闭失败
     */
    public boolean closePayment(String connectorPaymentId) {
        if (isDryRun()) {
            localState.put(connectorPaymentId, PaymentStatus.CANCELLED);
            log.info("[WeChat DRY-RUN] 关闭订单模拟成功: {}", connectorPaymentId);
            return true;
        }
        try {
            String apiPath = "/v3/pay/transactions/out-trade-no/" + connectorPaymentId + "/close";
            Map<String, Object> closeBody = new LinkedHashMap<>();
            closeBody.put("mchid", mchId);
            String json = objectMapper.writeValueAsString(closeBody);
            HttpHeaders headers = buildSignedHeaders("POST", apiPath, json);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            restTemplate.postForEntity(apiBase + apiPath, entity, Map.class);
            localState.put(connectorPaymentId, PaymentStatus.CANCELLED);
            log.info("[WeChat] 关闭订单成功: out_trade_no={}", connectorPaymentId);
            return true;
        } catch (Exception e) {
            log.error("[WeChat] closePayment failed for {}: {}", connectorPaymentId, e.getMessage());
            return false;
        }
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (isDryRun()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("wechat_refund_" + connectorPaymentId);
        }
        try {
            // 退款 API：POST /v3/refund/domestic/refunds
            String apiPath = "/v3/refund/domestic/refunds";
            Map<String, Object> refundBody = new LinkedHashMap<>();
            refundBody.put("out_trade_no", connectorPaymentId);
            refundBody.put("out_refund_no", "refund_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            Map<String, Object> refundAmount = new LinkedHashMap<>();
            refundAmount.put("refund", amount);
            refundAmount.put("total", amount);
            refundAmount.put("currency", "CNY");
            refundBody.put("amount", refundAmount);
            String json = objectMapper.writeValueAsString(refundBody);
            HttpHeaders headers = buildSignedHeaders("POST", apiPath, json);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + apiPath, entity, Map.class);

            if (resp.getBody() != null) {
                String refundId = String.valueOf(resp.getBody().getOrDefault("refund_id", connectorPaymentId));
                String refundStatus = String.valueOf(resp.getBody().getOrDefault("refund_status", "SUCCESS"));
                if ("SUCCESS".equals(refundStatus) || "PROCESSING".equals(refundStatus)) {
                    localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
                    log.info("[WeChat] 退款成功: out_trade_no={} refund_id={} status={}", connectorPaymentId, refundId, refundStatus);
                    return ConnectorRefundResult.ok(refundId);
                }
                return ConnectorRefundResult.fail("WeChat Pay refund status: " + refundStatus);
            }
            return ConnectorRefundResult.fail("WeChat Pay refund returned empty response");
        } catch (Exception e) {
            log.error("[WeChat] refund failed for {}: {}", connectorPaymentId, e.getMessage());
            return ConnectorRefundResult.fail("WeChat Pay refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        if (isDryRun()) return ConnectorHealth.up(getId(), 0); // dry-run always healthy
        long start = System.currentTimeMillis();
        try {
            // 微信支付无标准健康端点，尝试查询一个不存在的订单
            String apiPath = "/v3/pay/transactions/out-trade-no/health_check_probe?mchid=" + mchId;
            HttpHeaders headers = buildSignedHeaders("GET", apiPath, "");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            restTemplate.exchange(apiBase + apiPath, HttpMethod.GET, entity, Map.class);
            return ConnectorHealth.up(getId(), System.currentTimeMillis() - start);
        } catch (Exception e) {
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
    public int feeBasisPoints() { return 60; } // 微信支付费率约 0.6%

    /**
     * 将微信支付状态码映射到 {@link PaymentStatus} 枚举。
     * 采用 fail-closed 策略：未知状态一律映射为 FAILED。
     */
    private PaymentStatus mapWeChatStatus(String wechatStatus) {
        return switch (wechatStatus) {
            case "SUCCESS" -> PaymentStatus.SUCCEEDED;
            case "REFUND" -> PaymentStatus.REFUNDED;
            case "NOTPAY", "USERPAYING" -> PaymentStatus.PROCESSING;
            case "CLOSED", "REVOKED" -> PaymentStatus.CANCELLED;
            case "PAYERROR" -> PaymentStatus.FAILED;
            default -> PaymentStatus.FAILED; // fail-closed
        };
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
