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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WeChat Pay Connector - integrates with WeChat Pay's Native (扫码) and JSAPI APIs.
 * Requires: nexus.connectors.wechat.api-key + app-id + mch-id in config.
 * In sandbox mode without a real key, operates in dry-run (simulates success).
 *
 * <p>支持 Native（扫码支付）和 JSAPI 两种支付方式，默认使用 Native。</p>
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

    private final RestTemplate restTemplate;
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();

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

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            // Dry-run mode: simulate success for development
            String id = "wechat_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[WeChat DRY-RUN] Payment created: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            return ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 构建微信支付 Native 下单请求体
            String description = request.getDescription() != null ? request.getDescription() : "";
            String json = String.format(
                "{\"appid\":\"%s\",\"mch_id\":\"%s\",\"out_trade_no\":\"%s\",\"description\":\"%s\",\"amount\":{\"total\":%d,\"currency\":\"CNY\"}}",
                appId, mchId, request.getPaymentId(), description, request.getAmount());

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + "/pay/native", entity, Map.class);

            if (resp.getBody() != null) {
                String codeUrl = String.valueOf(resp.getBody().getOrDefault("code_url", ""));
                String prepayId = String.valueOf(resp.getBody().getOrDefault("prepay_id", ""));
                // 使用 out_trade_no 作为 connector payment ID，便于后续查询
                String connectorPaymentId = request.getPaymentId();
                PaymentStatus mapped = PaymentStatus.PROCESSING; // Native 下单后默认为处理中，等待用户扫码支付
                localState.put(connectorPaymentId, mapped);
                log.info("[WeChat] Native payment created: out_trade_no={} code_url={}", connectorPaymentId, codeUrl);

                ConnectorPaymentResult result = ConnectorPaymentResult.ok(connectorPaymentId, mapped);
                // 设置扫码链接为 redirectUrl，供前端生成二维码
                if (!codeUrl.isEmpty()) {
                    result.setRedirectUrl(codeUrl);
                } else if (!prepayId.isEmpty()) {
                    result.setRedirectUrl(prepayId);
                }
                return result;
            }
            return ConnectorPaymentResult.fail("WeChat Pay returned empty response");
        } catch (RuntimeException e) {
            log.error("[WeChat] createPayment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("WeChat Pay error: " + e.getMessage());
        }
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        if (apiKey == null || apiKey.isBlank()) {
            return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String json = String.format(
                "{\"appid\":\"%s\",\"mch_id\":\"%s\",\"out_trade_no\":\"%s\"}",
                appId, mchId, connectorPaymentId);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + "/pay/orderquery", entity, Map.class);

            if (resp.getBody() != null) {
                String tradeState = String.valueOf(resp.getBody().getOrDefault("trade_state", "UNKNOWN"));
                PaymentStatus mapped = mapWeChatStatus(tradeState);
                localState.put(connectorPaymentId, mapped);
                log.info("[WeChat] queryPayment: out_trade_no={} trade_state={} -> {}", connectorPaymentId, tradeState, mapped);
                return mapped;
            }
        } catch (RuntimeException e) {
            log.warn("[WeChat] queryPayment failed for {}: {}", connectorPaymentId, e.getMessage());
        }
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (apiKey == null || apiKey.isBlank()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("wechat_refund_" + connectorPaymentId);
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String json = String.format(
                "{\"appid\":\"%s\",\"mch_id\":\"%s\",\"out_trade_no\":\"%s\",\"refund_amount\":{\"total\":%d,\"currency\":\"CNY\"}}",
                appId, mchId, connectorPaymentId, amount);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBase + "/secapi/pay/refund", entity, Map.class);

            if (resp.getBody() != null) {
                String refundId = String.valueOf(resp.getBody().getOrDefault("refund_id", connectorPaymentId));
                localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
                log.info("[WeChat] refund: out_trade_no={} refund_id={}", connectorPaymentId, refundId);
                return ConnectorRefundResult.ok(refundId);
            }
            return ConnectorRefundResult.fail("WeChat Pay refund returned empty response");
        } catch (RuntimeException e) {
            log.error("[WeChat] refund failed for {}: {}", connectorPaymentId, e.getMessage());
            return ConnectorRefundResult.fail("WeChat Pay refund error: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        if (apiKey == null || apiKey.isBlank()) return ConnectorHealth.up(getId(), 0); // dry-run always healthy
        long start = System.currentTimeMillis();
        try {
            // 微信支付无标准健康端点，尝试查询一个不存在的订单
            // 如果返回非网络错误（如订单不存在），说明 API 可达，认为健康
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            String json = String.format(
                "{\"appid\":\"%s\",\"mch_id\":\"%s\",\"out_trade_no\":\"health_check_probe\"}",
                appId, mchId);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            restTemplate.postForEntity(apiBase + "/pay/orderquery", entity, Map.class);
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
}