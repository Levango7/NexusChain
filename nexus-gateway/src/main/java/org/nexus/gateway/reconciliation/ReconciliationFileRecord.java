package org.nexus.gateway.reconciliation;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账文件记录 JPA 实体。
 *
 * <p>持久化对账文件的元数据（不持久化文件内容本身），下载时根据元数据重新生成
 * CSV 或 JSON 格式的对账文件内容。每条记录对应一次日度或月度对账文件生成。</p>
 */
@Entity
@Table(name = "reconciliation_file_records")
public class ReconciliationFileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 文件格式类型（CSV 或 JSON） */
    @Column(name = "file_type", nullable = false, length = 10)
    private String fileType;

    /** 对账周期类型（DAILY 或 MONTHLY） */
    @Column(name = "period_type", nullable = false, length = 10)
    private String periodType;

    /** 对账周期开始日期 */
    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** 对账周期结束日期 */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** 总交易笔数 */
    @Column(name = "total_transactions", nullable = false)
    private long totalTransactions;

    /** 总交易金额 */
    @Column(name = "total_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal totalAmount;

    /** 匹配数 */
    @Column(name = "matched_count", nullable = false)
    private long matchedCount;

    /** 差错数 */
    @Column(name = "discrepancy_count", nullable = false)
    private long discrepancyCount;

    /** 文件下载 URL */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /** 文件大小（字节） */
    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    /** 文件生成时间 */
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    /** 记录创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getPeriodType() { return periodType; }
    public void setPeriodType(String periodType) { this.periodType = periodType; }

    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }

    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }

    public long getTotalTransactions() { return totalTransactions; }
    public void setTotalTransactions(long totalTransactions) { this.totalTransactions = totalTransactions; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public long getMatchedCount() { return matchedCount; }
    public void setMatchedCount(long matchedCount) { this.matchedCount = matchedCount; }

    public long getDiscrepancyCount() { return discrepancyCount; }
    public void setDiscrepancyCount(long discrepancyCount) { this.discrepancyCount = discrepancyCount; }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}