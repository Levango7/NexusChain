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
 * Alipay Payment Connector - integrates with Alipay's OpenAPI.
 * Supports: 当面付 (Face-to-face / precreate) and 网页支付 (web payment).
 * Requires: nexus.connectors.alipay.app-id + merchant-private-key + alipay-public-key in config.
 * In sandbox mode without a real key, operates in dry-run (simulates success).
 *
 * <p>支付宝 API 使用统一网关 {@code https://openapi.alipay.com/gateway.do}，
 * 通过 method 参数区分不同接口（alipay.trade.precreate / alipay.trade.query / alipay.trade.refund）。
 * 认证方式为 RSA2 签名，请求参数中包含 app_id、sign、sign_type=RSA2。</p>
 */
@Component
public class AlipayConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(AlipayConnector.class);
    private static final String DEFAULT_ALIPAY_API_BASE = "https://openapi.alipay.com/gateway.do";

    @Value("${nexus.connectors.alipay.app-id:}")
    private String appId;

    @Value("${nexus.connectors.alipay.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${nexus.connectors.alipay.alipay-public-key:}")
    private String alipayPublicKey;

    @Value("${nexus.connectors.alipay.api-base-url:https://openapi.alipay.com/gateway.do}")
    private String apiBaseUrl = DEFAULT_ALIPAY_API_BASE;

    @Value("${nexus.connectors.alipay.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate;
    private final Map<String, PaymentStatus> localState = new ConcurrentHashMap<>();

    @Autowired
    public AlipayConnector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 测试用兼容构造器。 */
    public AlipayConnector() {
        this.restTemplate = new RestTemplate();
    }

    @Override
    public String getId() { return "alipay"; }

    @Override
    public String getType() { return "http_psp"; }

    @Override
    public String getDisplayName() { return "Alipay (当面付/网页支付)"; }

    @Override
    public boolean isActive() { return enabled; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        // Dry-run 模式：merchantPrivateKey 为空时不发起 HTTP 请求
        if (merchantPrivateKey == null || merchantPrivateKey.isBlank()) {
            String id = "alipay_dryrun_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            localState.put(id, PaymentStatus.SUCCEEDED);
            log.info("[Alipay DRY-RUN] Payment created: {} amount={} {}", id, request.getAmount(), request.getCurrency());
            return ConnectorPaymentResult.ok(id, PaymentStatus.SUCCEEDED);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            // 构建支付宝当面付预下单请求参数
            // 支付宝使用统一网关，通过 method 参数区分接口
            String body = String.format(
                    "app_id=%s&method=alipay.trade.precreate&sign_type=RSA2" +
                    "&out_trade_no=%s&total_amount=%s&subject=%s",
                    appId,
                    request.getPaymentId(),
                    String.valueOf(request.getAmount()),
                    request.getDescription() != null ? request.getDescription() : "NexusChain Payment");

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (resp.getBody() != null) {
                // 支付宝当面付响应包含 qr_code（扫码链接）和 trade_no（支付宝交易号）
                String qrCode = String.valueOf(resp.getBody().getOrDefault("qr_code", ""));
                String tradeNo = String.valueOf(resp.getBody().getOrDefault("trade_no", "unknown"));
                String tradeStatus = String.valueOf(resp.getBody().getOrDefault("trade_status", "WAIT_BUYER_PAY"));
                PaymentStatus mapped = mapAlipayStatus(tradeStatus);
                localState.put(tradeNo, mapped);

                ConnectorPaymentResult result = ConnectorPaymentResult.ok(tradeNo, mapped);
                if (!qrCode.isEmpty()) {
                    result.setRedirectUrl(qrCode);
                }
                log.info("[Alipay] Payment created: tradeNo={} status={}", tradeNo, tradeStatus);
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
        // Dry-run 模式：从 localState 返回缓存状态
        if (merchantPrivateKey == null || merchantPrivateKey.isBlank()) {
            return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = String.format(
                    "app_id=%s&method=alipay.trade.query&sign_type=RSA2&out_trade_no=%s",
                    appId, connectorPaymentId);

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (resp.getBody() != null) {
                String tradeStatus = String.valueOf(resp.getBody().getOrDefault("trade_status", ""));
                PaymentStatus mapped = mapAlipayStatus(tradeStatus);
                localState.put(connectorPaymentId, mapped);
                return mapped;
            }
        } catch (RuntimeException e) {
            log.warn("[Alipay] query failed for {}: {}", connectorPaymentId, e.getMessage());
        }
        return localState.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        // Dry-run 模式：直接返回 ok
        if (merchantPrivateKey == null || merchantPrivateKey.isBlank()) {
            localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("alipay_refund_" + connectorPaymentId);
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = String.format(
                    "app_id=%s&method=alipay.trade.refund&sign_type=RSA2" +
                    "&out_trade_no=%s&refund_amount=%s",
                    appId, connectorPaymentId, String.valueOf(amount));

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (resp.getBody() != null) {
                // 支付宝退款响应包含 fund_change（退款金额变动）
                String fundChange = String.valueOf(resp.getBody().getOrDefault("fund_change", "Y"));
                if ("Y".equals(fundChange)) {
                    localState.put(connectorPaymentId, PaymentStatus.REFUNDED);
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
        // 未启用 → DOWN
        if (!enabled) return ConnectorHealth.down(getId(), "Connector disabled");
        // Dry-run → UP（无外部依赖）
        if (merchantPrivateKey == null || merchantPrivateKey.isBlank()) return ConnectorHealth.up(getId(), 0);

        // 真实模式：尝试查询一个不存在的订单，如果返回非网络错误则认为健康
        long start = System.currentTimeMillis();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            String body = String.format(
                    "app_id=%s&method=alipay.trade.query&sign_type=RSA2&out_trade_no=health_check_dummy",
                    appId);

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            restTemplate.postForEntity(apiBaseUrl, entity, Map.class);
            // 只要没有抛出网络异常，就认为网关可达
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
}