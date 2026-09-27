package org.nexus.gateway.reconciliation.report;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账报表记录 JPA 实体 — 存储 T+1 对账报表的元数据和汇总统计。
 */
@Entity
@Table(name = "reconciliation_report_records")
public class ReconciliationReportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;

    @Column(name = "channel_type", length = 16)
    private String channelType;

    @Column(name = "file_format", length = 8, nullable = false)
    private String fileFormat;

    @Column(name = "total_transactions")
    private Integer totalTransactions = 0;

    @Column(name = "matched_count")
    private Integer matchedCount = 0;

    @Column(name = "discrepancy_count")
    private Integer discrepancyCount = 0;

    @Column(name = "compensation_count")
    private Integer compensationCount = 0;

    @Column(name = "suspense_count")
    private Integer suspenseCount = 0;

    @Column(name = "total_discrepancy_amount", precision = 36, scale = 2)
    private BigDecimal totalDiscrepancyAmount = BigDecimal.ZERO;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes = 0L;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        if (this.generatedAt == null) {
            this.generatedAt = this.createdAt;
        }
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }

    public Integer getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(Integer totalTransactions) { this.totalTransactions = totalTransactions; }

    public Integer getMatchedCount() { return matchedCount; }
    public void setMatchedCount(Integer matchedCount) { this.matchedCount = matchedCount; }

    public Integer getDiscrepancyCount() { return discrepancyCount; }
    public void setDiscrepancyCount(Integer discrepancyCount) { this.discrepancyCount = discrepancyCount; }

    public Integer getCompensationCount() { return compensationCount; }
    public void setCompensationCount(Integer compensationCount) { this.compensationCount = compensationCount; }

    public Integer getSuspenseCount() { return suspenseCount; }
    public void setSuspenseCount(Integer suspenseCount) { this.suspenseCount = suspenseCount; }

    public BigDecimal getTotalDiscrepancyAmount() { return totalDiscrepancyAmount; }
    public void setTotalDiscrepancyAmount(BigDecimal totalDiscrepancyAmount) { this.totalDiscrepancyAmount = totalDiscrepancyAmount; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}