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
 *   <li><b>ALIPAY</b>：<b>fail-closed</b> —— 支付宝 {@code alipay.trade.refund} 的
 *       按 {@code trade_no} 退款尚未接入（需复用/搬移连接器的 RSA 请求组装，属独立批次），
 *       本服务显式报错而不是猜。</li>
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
                throw new IllegalStateException(
                        "支付宝原路退回尚未接入（alipay.trade.refund 按 trade_no 退款属独立批次）——"
                                + "请人工核对后走运营退款流程（fail-closed）");
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
