package org.nexus.gateway.reconciliation.bill;

import org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil;
import org.nexus.gateway.reconciliation.ChannelRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 微信支付对账单下载客户端。
 *
 * <p>支持两种模式：</p>
 * <ul>
 *   <li><b>dry-run 模式</b>（sandbox=true 或 merchantPrivateKey 为空）：返回模拟 CSV 数据，不发起真实 API 调用</li>
 *   <li><b>真实模式</b>：调用微信支付 V3 API {@code GET /v3/bill/tradebill} 获取 download_url，再下载 CSV/GZIP 内容</li>
 * </ul>
 *
 * <p>真实模式下使用 RSA-SHA256 签名，Authorization 头包含 5 字段（mchid, serial_no, timestamp, nonce_str, signature）。</p>
 */
@Component
public class WeChatBillDownloadClient {

    private static final Logger log = LoggerFactory.getLogger(WeChatBillDownloadClient.class);

    private final RestTemplate restTemplate;

    @Value("${nexus.connectors.wechat.api-base:https://api.mch.weixin.qq.com}")
    private String apiBase;

    @Value("${nexus.connectors.wechat.mch-id:}")
    private String mchId;

    @Value("${nexus.connectors.wechat.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${nexus.connectors.wechat.cert-serial-no:}")
    private String certSerialNo;

    @Value("${nexus.connectors.wechat.sandbox:true}")
    private boolean sandbox;

    @Value("${nexus.connectors.wechat.enabled:false}")
    private boolean enabled;

    @Value("${nexus.reconciliation.bill.timeout-seconds:30}")
    private int timeoutSeconds;

    public WeChatBillDownloadClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 下载微信支付对账单。
     *
     * @param merchantId 商户ID（用于日志标识）
     * @param billDate    账单日期（如 2026-09-27）
     * @return CSV 格式的对账单内容
     */
    public String downloadBill(Long merchantId, String billDate) {
        if (!enabled) {
            log.warn("[WeChatBill] 微信渠道未启用，返回空对账单");
            return "";
        }

        if (isDryRun()) {
            log.info("[WeChatBill] dry-run 模式，返回模拟 CSV 数据: merchantId={}, billDate={}", merchantId, billDate);
            return buildMockCsvData(billDate);
        }

        log.info("[WeChatBill] 真实模式，下载对账单: merchantId={}, billDate={}", merchantId, billDate);

        // 1. 调用 /v3/bill/tradebill 获取 download_url
        String downloadUrl = fetchDownloadUrl(billDate);
        if (downloadUrl == null) {
            log.error("[WeChatBill] 获取 download_url 失败: billDate={}", billDate);
            return "";
        }

        // 2. 通过 download_url 下载 CSV/GZIP 内容
        return downloadFileContent(downloadUrl);
    }

    /**
     * 判断是否为 dry-run 模式。
     *
     * @return true 表示 dry-run（sandbox=true 或 merchantPrivateKey 为空）
     */
    public boolean isDryRun() {
        return sandbox || merchantPrivateKey == null || merchantPrivateKey.isBlank();
    }

    // ==================== 真实模式 API 调用 ====================

    /**
     * 调用微信支付 V3 API 获取对账单下载链接。
     *
     * @param billDate 账单日期
     * @return download_url，或 null（失败时）
     */
    private String fetchDownloadUrl(String billDate) {
        try {
            String url = apiBase + "/v3/bill/tradebill?bill_date=" + billDate + "&bill_type=ALL";

            HttpHeaders headers = buildAuthHeaders("GET", "/v3/bill/tradebill?bill_date=" + billDate + "&bill_type=ALL");
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object downloadUrl = response.getBody().get("download_url");
                if (downloadUrl != null) {
                    return downloadUrl.toString();
                }
            }

            log.error("[WeChatBill] 获取 download_url 失败: status={}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("[WeChatBill] 获取 download_url 异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 通过 download_url 下载对账单文件内容。
     *
     * @param downloadUrl 微信提供的下载链接
     * @return CSV 格式的对账单内容
     */
    private String downloadFileContent(String downloadUrl) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    downloadUrl, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }

            log.error("[WeChatBill] 下载对账单失败: status={}", response.getStatusCode());
            return "";
        } catch (Exception e) {
            log.error("[WeChatBill] 下载对账单异常: {}", e.getMessage(), e);
            return "";
        }
    }

    /**
     * 构建微信支付 V3 API 的 Authorization 头。
     *
     * <p>格式：WECHATPAY2-SHA256-RSA2048 包含 mchid, serial_no, timestamp, nonce_str, signature</p>
     */
    private HttpHeaders buildAuthHeaders(String method, String urlPath) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = UUID.randomUUID().toString().replace("-", "");

        // P0 修复（2026-10-09）：此前 signature 硬编码 "placeholder" —— 真实模式下必被
        // 微信拒绝（失败被 catch 吞成空对账单），日终对账永远拿不到真实账单。
        // 现以商户 RSA 私钥按 V3 规范签名（SHA256withRSA，签名串 method\nurl\ntimestamp\nnonce\nbody\n），
        // 与回调验签侧共用同一签名工具。
        // 签名失败 fail-closed：抛 IllegalStateException → 上层返回空内容 → 对账跳过该商户，
        // 绝不发送未签名请求、也不伪造内容。
        final String signature;
        try {
            signature = WeChatPaySignatureUtil.generateRsaSignature(
                    method, urlPath, timestamp, nonceStr, "", merchantPrivateKey);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "[WeChatBill] 商户私钥签名失败，拒绝发送未签名请求: " + e.getMessage(), e);
        }

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

    // ==================== 模拟数据 ====================

    /**
     * 构建模拟微信对账单 CSV 数据。
     *
     * <p>格式参考微信支付 V3 对账单：汇总行(#开头) + 列标题行 + 数据行</p>
     */
    private String buildMockCsvData(String billDate) {
        StringBuilder sb = new StringBuilder();
        // 汇总行
        sb.append("# 微信支付对账单 ").append(billDate).append("\n");
        sb.append("# 总交易数: 3, 总金额: 800.00\n");
        // 列标题行
        sb.append("商户订单号,总金额,交易状态,交易时间,货币种类\n");
        // 数据行
        sb.append("mock_wx_order_001,100.00,SUCCESS,").append(billDate).append("T10:00:00+08:00,CNY\n");
        sb.append("mock_wx_order_002,300.00,REFUND,").append(billDate).append("T11:00:00+08:00,CNY\n");
        sb.append("mock_wx_order_003,400.00,NOTPAY,").append(billDate).append("T12:00:00+08:00,CNY\n");
        return sb.toString();
    }
}