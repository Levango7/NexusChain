package org.nexus.gateway.reconciliation.bill;

import org.nexus.gateway.reconciliation.ChannelRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 对账单下载代理 — 根据渠道类型委托给对应的对账单下载客户端。
 *
 * <p>支持的渠道类型：</p>
 * <ul>
 *   <li><b>WECHAT</b> — 微信支付，委托给 {@link WeChatBillDownloadClient}</li>
 *   <li><b>ALIPAY</b> — 支付宝，委托给 {@link AlipayBillDownloadClient}</li>
 * </ul>
 *
 * <p>下载流程：下载原始 CSV → 解析为 ChannelRecord 列表 → 封装为 BillDownloadResult</p>
 */
@Service
public class BillDownloadProxy {

    private static final Logger log = LoggerFactory.getLogger(BillDownloadProxy.class);

    private final WeChatBillDownloadClient weChatClient;
    private final AlipayBillDownloadClient alipayClient;
    private final WeChatBillParser weChatParser;
    private final AlipayBillParser alipayParser;

    public BillDownloadProxy(WeChatBillDownloadClient weChatClient,
                              AlipayBillDownloadClient alipayClient,
                              WeChatBillParser weChatParser,
                              AlipayBillParser alipayParser) {
        this.weChatClient = weChatClient;
        this.alipayClient = alipayClient;
        this.weChatParser = weChatParser;
        this.alipayParser = alipayParser;
    }

    /**
     * 下载对账单。
     *
     * @param channelType 渠道类型（WECHAT / ALIPAY）
     * @param merchantId  商户 ID
     * @param billDate    账单日期（如 2026-09-27）
     * @return 下载结果
     */
    public BillDownloadResult downloadBill(String channelType, Long merchantId, String billDate) {
        if (channelType == null || channelType.isBlank()) {
            return BillDownloadResult.failure(null, merchantId, billDate, "渠道类型不能为空");
        }

        log.info("[BillDownloadProxy] 下载对账单: channelType={}, merchantId={}, billDate={}",
                channelType, merchantId, billDate);

        try {
            switch (channelType.toUpperCase()) {
                case "WECHAT":
                    return downloadWeChatBill(merchantId, billDate);
                case "ALIPAY":
                    return downloadAlipayBill(merchantId, billDate);
                default:
                    return BillDownloadResult.failure(channelType, merchantId, billDate,
                            "不支持的渠道类型: " + channelType);
            }
        } catch (Exception e) {
            log.error("[BillDownloadProxy] 下载对账单异常: channelType={}, error={}",
                    channelType, e.getMessage(), e);
            return BillDownloadResult.failure(channelType, merchantId, billDate, e.getMessage());
        }
    }

    /**
     * 下载微信对账单。
     */
    private BillDownloadResult downloadWeChatBill(Long merchantId, String billDate) {
        String csvContent = weChatClient.downloadBill(merchantId, billDate);
        if (csvContent == null || csvContent.isBlank()) {
            return BillDownloadResult.failure("WECHAT", merchantId, billDate, "微信对账单内容为空");
        }

        List<ChannelRecord> records = weChatParser.parse(csvContent);
        boolean dryRun = weChatClient.isDryRun();

        log.info("[BillDownloadProxy] 微信对账单下载完成: records={}, dryRun={}",
                records.size(), dryRun);

        return BillDownloadResult.success("WECHAT", merchantId, billDate,
                csvContent, records, dryRun);
    }

    /**
     * 下载支付宝对账单。
     */
    private BillDownloadResult downloadAlipayBill(Long merchantId, String billDate) {
        String csvContent = alipayClient.downloadBill(merchantId, billDate);
        if (csvContent == null || csvContent.isBlank()) {
            return BillDownloadResult.failure("ALIPAY", merchantId, billDate, "支付宝对账单内容为空");
        }

        List<ChannelRecord> records = alipayParser.parse(csvContent);
        boolean dryRun = alipayClient.isDryRun();

        log.info("[BillDownloadProxy] 支付宝对账单下载完成: records={}, dryRun={}",
                records.size(), dryRun);

        return BillDownloadResult.success("ALIPAY", merchantId, billDate,
                csvContent, records, dryRun);
    }

    /**
     * 检查指定渠道是否为 dry-run 模式。
     */
    public boolean isDryRun(String channelType) {
        if (channelType == null) {
            return true;
        }
        switch (channelType.toUpperCase()) {
            case "WECHAT":
                return weChatClient.isDryRun();
            case "ALIPAY":
                return alipayClient.isDryRun();
            default:
                return true;
        }
    }
}