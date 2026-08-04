package org.nexus.compliance.aml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 可疑交易报告（STR）实体。
 * <p>
 * 描述一笔可疑交易的上报内容与上报状态。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuspiciousTransactionReport {

    /** 报告 ID */
    @JsonProperty("reportId")
    private String reportId;

    /** 交易详情 */
    @JsonProperty("transactionDetail")
    private String transactionDetail;

    /** 可疑原因 */
    @JsonProperty("suspiciousReason")
    private String suspiciousReason;

    /** 上报状态 */
    @JsonProperty("reportStatus")
    private ReportStatus reportStatus;

    /** 上报时间 */
    @JsonProperty("reportedAt")
    private Instant reportedAt;

    /** 上报状态枚举 */
    public enum ReportStatus {
        DRAFT,
        SUBMITTED,
        ACKNOWLEDGED,
        REJECTED
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }

    public String getTransactionDetail() { return transactionDetail; }
    public void setTransactionDetail(String transactionDetail) { this.transactionDetail = transactionDetail; }

    public String getSuspiciousReason() { return suspiciousReason; }
    public void setSuspiciousReason(String suspiciousReason) { this.suspiciousReason = suspiciousReason; }

    public ReportStatus getReportStatus() { return reportStatus; }
    public void setReportStatus(ReportStatus reportStatus) { this.reportStatus = reportStatus; }

    public Instant getReportedAt() { return reportedAt; }
    public void setReportedAt(Instant reportedAt) { this.reportedAt = reportedAt; }
}