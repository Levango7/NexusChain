package org.nexus.gateway.reconciliation.bill;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对账单下载结果 DTO。
 *
 * <p>封装对账单下载的完整结果信息，包括下载内容、解析后的记录列表、
 * 下载状态和元数据。</p>
 */
public class BillDownloadResult {

    /** 渠道类型 */
    private String channelType;

    /** 商户 ID */
    private Long merchantId;

    /** 账单日期 */
    private String billDate;

    /** 原始 CSV 内容 */
    private String rawContent;

    /** 解析后的渠道记录列表 */
    private List<org.nexus.gateway.reconciliation.ChannelRecord> records;

    /** 是否成功 */
    private boolean success;

    /** 错误信息（失败时） */
    private String errorMessage;

    /** 是否 dry-run 模式 */
    private boolean dryRun;

    /** 下载时间 */
    private LocalDateTime downloadedAt;

    public BillDownloadResult() {
        this.downloadedAt = LocalDateTime.now();
    }

    /**
     * 创建成功结果。
     */
    public static BillDownloadResult success(String channelType, Long merchantId, String billDate,
                                              String rawContent,
                                              List<org.nexus.gateway.reconciliation.ChannelRecord> records,
                                              boolean dryRun) {
        BillDownloadResult result = new BillDownloadResult();
        result.channelType = channelType;
        result.merchantId = merchantId;
        result.billDate = billDate;
        result.rawContent = rawContent;
        result.records = records;
        result.success = true;
        result.dryRun = dryRun;
        return result;
    }

    /**
     * 创建失败结果。
     */
    public static BillDownloadResult failure(String channelType, Long merchantId, String billDate,
                                              String errorMessage) {
        BillDownloadResult result = new BillDownloadResult();
        result.channelType = channelType;
        result.merchantId = merchantId;
        result.billDate = billDate;
        result.success = false;
        result.errorMessage = errorMessage;
        return result;
    }

    // === Getters & Setters ===

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getBillDate() { return billDate; }
    public void setBillDate(String billDate) { this.billDate = billDate; }

    public String getRawContent() { return rawContent; }
    public void setRawContent(String rawContent) { this.rawContent = rawContent; }

    public List<org.nexus.gateway.reconciliation.ChannelRecord> getRecords() { return records; }
    public void setRecords(List<org.nexus.gateway.reconciliation.ChannelRecord> records) { this.records = records; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public LocalDateTime getDownloadedAt() { return downloadedAt; }
    public void setDownloadedAt(LocalDateTime downloadedAt) { this.downloadedAt = downloadedAt; }

    /**
     * 获取记录数量。
     */
    public int getRecordCount() {
        return records != null ? records.size() : 0;
    }
}