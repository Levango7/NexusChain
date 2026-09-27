package org.nexus.gateway.reconciliation.bill;

import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * 支付宝对账单下载客户端。
 *
 * <p>支持两种模式：</p>
 * <ul>
 *   <li><b>dry-run 模式</b>（sandbox=true 或 merchantPrivateKey 为空）：返回模拟 CSV 数据</li>
 *   <li><b>真实模式</b>：调用 {@code alipay.data.dataservice.bill.downloadurl.query} 获取下载链接，再下载 CSV 内容</li>
 * </ul>
 *
 * <p>真实模式下使用 RSA2 签名，bizContent 通过 ObjectMapper 序列化。</p>
 */
@Component
public class AlipayBillDownloadClient {

    private static final Logger log = LoggerFactory.getLogger(AlipayBillDownloadClient.class);

    private final RestTemplate restTemplate;

    @Value("${nexus.connectors.alipay.api-base-url:https://openapi.alipay.com/gateway.do}")
    private String apiBaseUrl;

    @Value("${nexus.connectors.alipay.app-id:}")
    private String appId;

    @Value("${nexus.connectors.alipay.merchant-private-key:}")
    private String merchantPrivateKey;

    @Value("${nexus.connectors.alipay.alipay-public-key:}")
    private String alipayPublicKey;

    @Value("${nexus.connectors.alipay.sandbox:true}")
    private boolean sandbox;

    @Value("${nexus.connectors.alipay.enabled:false}")
    private boolean enabled;

    @Value("${nexus.reconciliation.bill.timeout-seconds:30}")
    private int timeoutSeconds;

    public AlipayBillDownloadClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 下载支付宝对账单。
     *
     * @param merchantId 商户ID（用于日志标识）
     * @param billDate    账单日期（如 2026-09-27）
     * @return CSV 格式的对账单内容
     */
    public String downloadBill(Long merchantId, String billDate) {
        if (!enabled) {
            log.warn("[AlipayBill] 支付宝渠道未启用，返回空对账单");
            return "";
        }

        if (isDryRun()) {
            log.info("[AlipayBill] dry-run 模式，返回模拟 CSV 数据: merchantId={}, billDate={}", merchantId, billDate);
            return buildMockCsvData(billDate);
        }

        log.info("[AlipayBill] 真实模式，下载对账单: merchantId={}, billDate={}", merchantId, billDate);

        // 1. 调用 alipay.data.dataservice.bill.downloadurl.query 获取下载链接
        String downloadUrl = fetchDownloadUrl(billDate);
        if (downloadUrl == null) {
            log.error("[AlipayBill] 获取下载链接失败: billDate={}", billDate);
            return "";
        }

        // 2. 下载 CSV 内容
        return downloadFileContent(downloadUrl);
    }

    /**
     * 判断是否为 dry-run 模式。
     */
    public boolean isDryRun() {
        return sandbox || merchantPrivateKey == null || merchantPrivateKey.isBlank();
    }

    // ==================== 真实模式 API 调用 ====================

    /**
     * 调用支付宝接口获取对账单下载链接。
     */
    private String fetchDownloadUrl(String billDate) {
        try {
            // 构造业务参数
            Map<String, String> bizParams = new LinkedHashMap<>();
            bizParams.put("bill_type", "trade");
            bizParams.put("bill_date", billDate);

            // 构造请求参数
            Map<String, String> params = new LinkedHashMap<>();
            params.put("app_id", appId);
            params.put("method", "alipay.data.dataservice.bill.downloadurl.query");
            params.put("charset", "UTF-8");
            params.put("sign_type", "RSA2");
            params.put("timestamp", java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            params.put("version", "1.0");
            params.put("biz_content", buildBizContent(bizParams));

            // 生成签名
            String sign = AlipaySignatureUtil.generateSignature(params, merchantPrivateKey);
            params.put("sign", sign);

            // 发送请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> formParams = new LinkedMultiValueMap<>();
            params.forEach(formParams::add);

            HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formParams, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiBaseUrl, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map body = response.getBody();
                // 支付宝响应格式: {"alipay_data_dataservice_bill_downloadurl_query_response": {"bill_download_url": "..."}}
                for (Object key : body.keySet()) {
                    if (key.toString().startsWith("alipay_") && body.get(key) instanceof Map) {
                        Map inner = (Map) body.get(key);
                        Object downloadUrl = inner.get("bill_download_url");
                        if (downloadUrl != null) {
                            return downloadUrl.toString();
                        }
                    }
                }
            }

            log.error("[AlipayBill] 获取下载链接失败: status={}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("[AlipayBill] 获取下载链接异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 构造 bizContent JSON。
     */
    private String buildBizContent(Map<String, String> bizParams) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : bizParams.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 下载对账单文件内容。
     */
    private String downloadFileContent(String downloadUrl) {
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(downloadUrl, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            }
            log.error("[AlipayBill] 下载对账单失败: status={}", response.getStatusCode());
            return "";
        } catch (Exception e) {
            log.error("[AlipayBill] 下载对账单异常: {}", e.getMessage(), e);
            return "";
        }
    }

    // ==================== 模拟数据 ====================

    /**
     * 构建模拟支付宝对账单 CSV 数据。
     */
    private String buildMockCsvData(String billDate) {
        StringBuilder sb = new StringBuilder();
        // 汇总行
        sb.append("# 支付宝对账单 ").append(billDate).append("\n");
        sb.append("# 总交易数: 3, 总金额: 800.00\n");
        // 列标题行
        sb.append("商户订单号,金额,状态,完成时间\n");
        // 数据行（支付宝时间格式: yyyy-MM-dd HH:mm:ss）
        sb.append("mock_alipay_order_001,100.00,TRADE_SUCCESS,").append(billDate).append(" 10:00:00\n");
        sb.append("mock_alipay_order_002,300.00,TRADE_REFUND,").append(billDate).append(" 11:00:00\n");
        sb.append("mock_alipay_order_003,400.00,WAIT_BUYER_PAY,").append(billDate).append(" 12:00:00\n");
        return sb.toString();
    }
}