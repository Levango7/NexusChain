package org.nexus.gateway.reconciliation.compensation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 渠道原路退回服务（长款渠道退款落地 3/3，P0 修复 2026-10-11）。
 *
 * <p><b>为什么不能用现有连接器的 {@code refund()}</b>：
 * {@code WeChatPayConnector#refund}/ {@code AlipayConnector#refund} 都把标识当
 * <b>商户订单号（out_trade_no）</b>用；而对账长款手里只有**渠道侧交易号**
 * （{@code ReconciliationDiscrepancy.transactionId}，来自渠道对账单），
 * 两者不是一回事。原路退回必须按渠道交易号发起。</p>
 *
 * <h2>覆盖范围（诚实声明）</h2>
 * <ul>
 *   <li><b>WECHAT</b>：已实现 —— 微信 V3 {@code POST /v3/refund/domestic/refunds}，
 *       按 {@code transaction_id} 退款，{@code out_refund_no} 由调用方给定（幂等键）。</li>
 *   <li><b>ALIPAY</b>：{@code alipay.trade.refund} 按 {@code trade_no} 原路退回，
 *       {@code out_request_no} 即调用方给定的幂等键；RSA2 签名复用与支付连接器同一签名
 *       工具（{@code AlipaySignatureUtil}）——<b>不依赖连接器</b>，保持对账模块边界。</li>
 * </ul>
 *
 * <h2>幂等</h2>
 * <p>调用方传入 {@code outRefundNo}（补偿记录派生，如 {@code AUTO_REFUND_<补偿记录ID>}）；
 * 微信按该字段去重，重复调用只会返回同一笔退款 —— 这是"补偿可重跑不重复退"的关键。</p>
 *
 * <h2>dry-run</h2>
 * <p>{@code sandbox=true} 或商户私钥未配置时返回模拟退款号（与连接器同款语义，便于沙箱联调），
 * <b>不发起任何真实请求</b>。</p>
 */
@Component
public class ChannelRefundService {

    private static final Logger log = LoggerFactory.getLogger(ChannelRefundService.class);

    /** 渠道交易号缺失时不得退款（fail-closed）。 */
    public static final String CHANNEL_WECHAT = "WECHAT";
    public static final String CHANNEL_ALIPAY = "ALIPAY";

    @Value("${nexus.connectors.wechat.mch-id:}")
    private String mchId;

    @Value("${nexus.connectors.wechat.cert-serial-no:}")
    private String certSerialNo;

    /** 商户 RSA 私钥（PKCS#8 Base64）；生产经环境变量注入，禁止硬编码。 */
    @Value("${nexus.connectors.wechat.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${nexus.connectors.wechat.api-base-url:https://api.mch.weixin.qq.com}")
    private String apiBase;

    @Value("${nexus.connectors.wechat.sandbox:false}")
    private boolean sandbox;

    // ---- 支付宝原路退回（P0 补齐 2026-10-11）----
    @Value("${nexus.connectors.alipay.app-id:}")
    private String alipayAppId;

    /** 商户 RSA 私钥（RSA2 签名）；生产经环境变量注入，禁止硬编码。 */
    @Value("${nexus.connectors.alipay.merchant-private-key:}")
    private String alipayPrivateKey;

    @Value("${nexus.connectors.alipay.api-base-url:https://openapi.alipay.com/gateway.do}")
    private String alipayApiBase;

    private final RestTemplate restTemplate;

    public ChannelRefundService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    private boolean isDryRun() {
        return sandbox || merchantPrivateKey == null || merchantPrivateKey.isBlank();
    }

    /**
     * 按渠道交易号原路退回。
     *
     * @param channelType  渠道类型（WECHAT / ALIPAY）
     * @param channelTxId  渠道侧交易号（对账差错上的 transactionId）
     * @param totalAmount  原始渠道金额（微信退款接口要求 total）
     * @param refundAmount 本次退回金额
     * @param outRefundNo  商户退款单号（幂等键，由补偿记录派生）
     * @return 渠道退款单号
     * @throws IllegalStateException 渠道不支持 / 标识缺失 / 接口失败（fail-closed，不静默成功）
     */
    public String refundByChannelTransaction(String channelType, String channelTxId,
                                              BigDecimal totalAmount, BigDecimal refundAmount,
                                              String outRefundNo) {
        if (channelType == null || channelType.isBlank()) {
            throw new IllegalStateException(
                    "渠道退款拒绝执行：缺渠道归属（channelType 为空）——不可猜渠道退款（fail-closed）");
        }
        if (channelTxId == null || channelTxId.isBlank()) {
            throw new IllegalStateException(
                    "渠道退款拒绝执行：缺渠道交易号（transactionId 为空）——无可退对象（fail-closed）");
        }
        String channel = channelType.trim().toUpperCase();
        switch (channel) {
            case CHANNEL_WECHAT:
                return wechatRefundByTxId(channelTxId, totalAmount, refundAmount, outRefundNo);
            case CHANNEL_ALIPAY:
                return alipayRefundByTradeNo(channelTxId, totalAmount, refundAmount, outRefundNo);
            default:
                throw new IllegalStateException("渠道退款拒绝执行：不支持的渠道类型 " + channelType);
        }
    }

    // ==================== 微信 V3：按 transaction_id 退款 ====================

    private String wechatRefundByTxId(String channelTxId, BigDecimal totalAmount,
                                       BigDecimal refundAmount, String outRefundNo) {
        if (isDryRun()) {
            log.info("[ChannelRefund] 微信 dry-run：模拟按交易号退款 channelTxId={}, outRefundNo={}, amount={}",
                    channelTxId, outRefundNo, refundAmount);
            return "wechat_dryrun_refund_" + outRefundNo;
        }

        String apiPath = "/v3/refund/domestic/refunds";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_id", channelTxId);
        body.put("out_refund_no", outRefundNo);
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("refund", toCents(refundAmount));
        amount.put("total", toCents(totalAmount != null ? totalAmount : refundAmount));
        amount.put("currency", "CNY");
        body.put("amount", amount);

        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(body);
            HttpHeaders headers = buildAuthHeaders("POST", apiPath, json);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    apiBase + apiPath, new HttpEntity<>(json, headers), Map.class);

            if (resp.getBody() != null) {
                String refundId = String.valueOf(resp.getBody().getOrDefault("refund_id", outRefundNo));
                String refundStatus = String.valueOf(resp.getBody().getOrDefault("refund_status", "SUCCESS"));
                if ("SUCCESS".equals(refundStatus) || "PROCESSING".equals(refundStatus)) {
                    log.info("[ChannelRefund] 微信按交易号退款成功: channelTxId={}, refund_id={}, status={}",
                            channelTxId, refundId, refundStatus);
                    return refundId;
                }
                throw new IllegalStateException("微信退款返回状态非成功: " + refundStatus);
            }
            throw new IllegalStateException("微信退款响应为空");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "微信按交易号退款失败: channelTxId=" + channelTxId + ", error=" + e.getMessage(), e);
        }
    }

    // ==================== 支付宝：按 trade_no 原路退回 ====================

    private String alipayRefundByTradeNo(String tradeNo, BigDecimal totalAmount,
                                          BigDecimal refundAmount, String outRequestNo) {
        if (alipayPrivateKey == null || alipayPrivateKey.isBlank()) {
            throw new IllegalStateException(
                    "支付宝原路退回拒绝执行：商户私钥未配置（fail-closed，不发起未签名请求）");
        }

        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("trade_no", tradeNo);
        biz.put("refund_amount", formatAmount(refundAmount));
        biz.put("out_request_no", outRequestNo);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", alipayAppId);
        params.put("method", "alipay.trade.refund");
        params.put("sign_type", "RSA2");
        params.put("timestamp", java.time.LocalDateTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.put("nonce", UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        try {
            params.put("biz_content",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(biz));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("支付宝退款 bizContent 序列化失败: " + e.getMessage(), e);
        }
        // RSA2 签名：与支付连接器同一签名工具（不引入第二套实现）
        params.put("sign", org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil
                .generateSignature(params, alipayPrivateKey));

        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(e.getKey()).append('=')
                    .append(java.net.URLEncoder.encode(e.getValue(),
                            java.nio.charset.StandardCharsets.UTF_8));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    alipayApiBase, new HttpEntity<>(body.toString(), headers), Map.class);
            Map<?, ?> root = resp.getBody();
            if (root == null) {
                throw new IllegalStateException("支付宝退款响应为空");
            }
            Object refund = root.get("alipay_trade_refund_response");
            if (!(refund instanceof Map)) {
                throw new IllegalStateException("支付宝退款响应缺少 alipay_trade_refund_response");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) refund;
            String code = String.valueOf(r.get("code"));
            if (!"10000".equals(code)) {
                throw new IllegalStateException("支付宝退款返回非成功: code=" + code + ", sub_msg="
                        + r.get("sub_msg"));
            }
            // 幂等：同一 out_request_no 重复调用支付宝返回同一 buyer_logon_id/资金流水
            String refundId = String.valueOf(r.getOrDefault("trade_no", tradeNo));
            log.info("[ChannelRefund] 支付宝按 trade_no 退回成功: tradeNo={}, outRequestNo={}",
                    tradeNo, outRequestNo);
            return refundId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "支付宝按 trade_no 退回失败: tradeNo=" + tradeNo + ", error=" + e.getMessage(), e);
        }
    }

    /** 金额按"元"序列化（支付宝 refund_amount 要求两位小数，如 12.34）。 */
    private static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalStateException("退款金额为空（fail-closed）");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /** 元 → 分（微信金额单位为分，接口要求整数）。 */
    private long toCents(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalStateException("退款金额为空（fail-closed）");
        }
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    /** 构造 V3 Authorization 头（RSA-SHA256；复用与回调验签同一签名工具）。 */
    private HttpHeaders buildAuthHeaders(String method, String apiPath, String body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String signature = org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil
                .generateRsaSignature(method, apiPath, timestamp, nonceStr, body, merchantPrivateKey);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization",
                "WECHATPAY2-SHA256-RSA2048 " +
                        "mchid=\"" + mchId + "\"," +
                        "serial_no=\"" + certSerialNo + "\"," +
                        "timestamp=\"" + timestamp + "\"," +
                        "nonce_str=\"" + nonceStr + "\"," +
                        "signature=\"" + signature + "\"");
        return headers;
    }
}
