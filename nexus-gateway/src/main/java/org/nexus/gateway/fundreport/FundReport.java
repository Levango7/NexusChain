package org.nexus.gateway.fundreport;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 资金报表实体 — JPA 映射 fund_reports 表。
 *
 * <p>记录每次生成的资金报表，包含报表编号、类型、格式、周期范围和内容。
 * 报表内容中账户编号已脱敏（仅保留后 4 位，前缀 ****）。</p>
 */
@Entity
@Table(name = "fund_reports")
public class FundReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报表编号，格式 FR{timestamp}{random} */
    @Column(name = "report_no", unique = true, nullable = false, length = 64)
    private String reportNo;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 报表类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 16)
    private ReportType reportType;

    /** 报表格式 */
    @Enumerated(EnumType.STRING)
    @Column(name = "report_format", nullable = false, length = 8)
    private ReportFormat reportFormat;

    /** 报表周期开始时间 */
    @Column(name = "period_start", nullable = false)
    private LocalDateTime periodStart;

    /** 报表周期结束时间 */
    @Column(name = "period_end", nullable = false)
    private LocalDateTime periodEnd;

    /** 报表内容（CSV 或 JSON 格式） */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 状态：GENERATED/FAILED */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "GENERATED";

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = "GENERATED";
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReportNo() { return reportNo; }
    public void setReportNo(String reportNo) { this.reportNo = reportNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public ReportType getReportType() { return reportType; }
    public void setReportType(ReportType reportType) { this.reportType = reportType; }

    public ReportFormat getReportFormat() { return reportFormat; }
    public void setReportFormat(ReportFormat reportFormat) { this.reportFormat = reportFormat; }

    public LocalDateTime getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDateTime periodStart) { this.periodStart = periodStart; }

    public LocalDateTime getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDateTime periodEnd) { this.periodEnd = periodEnd; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}