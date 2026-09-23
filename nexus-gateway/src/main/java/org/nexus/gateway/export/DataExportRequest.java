package org.nexus.gateway.export;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 数据导出请求实体。
 *
 * <p>记录商户发起的数据导出请求的完整生命周期：从创建（PENDING）到处理中
 * （PROCESSING）再到完成（COMPLETED）或失败（FAILED），以及过期清理（EXPIRED）。
 * 导出文件存储在本地文件系统，路径格式为 {@code data/exports/{merchantId}/{requestId}.{format}}。</p>
 *
 * <p>状态流转：
 * <ul>
 *   <li>{@link Status#PENDING} → {@link Status#PROCESSING} → {@link Status#COMPLETED}</li>
 *   <li>{@link Status#PENDING} → {@link Status#PROCESSING} → {@link Status#FAILED}</li>
 *   <li>{@link Status#COMPLETED} → {@link Status#EXPIRED}（定时清理任务标记）</li>
 * </ul>
 */
@Entity
@Table(name = "data_export_requests", indexes = {
        @Index(name = "idx_der_merchant_id", columnList = "merchant_id"),
        @Index(name = "idx_der_status", columnList = "status"),
        @Index(name = "idx_der_merchant_status", columnList = "merchant_id, status"),
        @Index(name = "idx_der_completed_at", columnList = "completed_at")
})
public class DataExportRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起导出的商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 导出数据类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "export_type", nullable = false, length = 32)
    private DataExportType exportType;

    /** 导出文件格式 */
    @Enumerated(EnumType.STRING)
    @Column(name = "format", nullable = false, length = 16)
    private DataExportFormat format;

    /** 请求状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.PENDING;

    /** 数据时间范围起始 */
    @Column(name = "date_from", nullable = false)
    private LocalDateTime dateFrom;

    /** 数据时间范围结束 */
    @Column(name = "date_to", nullable = false)
    private LocalDateTime dateTo;

    /** 额外过滤条件（JSON 格式） */
    @Column(name = "filters", length = 2048)
    private String filters;

    /** 生成文件的存储路径 */
    @Column(name = "file_path", length = 512)
    private String filePath;

    /** 文件大小（字节） */
    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    /** 导出记录数 */
    @Column(name = "record_count")
    private Integer recordCount;

    /** 请求创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 处理完成时间 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 过期时间（清理任务设置） */
    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    /** 失败时的错误信息 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Enumerations ---

    /**
     * 导出请求状态枚举。
     */
    public enum Status {
        /** 待处理 */
        PENDING,
        /** 处理中 */
        PROCESSING,
        /** 已完成 */
        COMPLETED,
        /** 已失败 */
        FAILED,
        /** 已过期 */
        EXPIRED
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public DataExportType getExportType() { return exportType; }
    public void setExportType(DataExportType exportType) { this.exportType = exportType; }

    public DataExportFormat getFormat() { return format; }
    public void setFormat(DataExportFormat format) { this.format = format; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getDateFrom() { return dateFrom; }
    public void setDateFrom(LocalDateTime dateFrom) { this.dateFrom = dateFrom; }

    public LocalDateTime getDateTo() { return dateTo; }
    public void setDateTo(LocalDateTime dateTo) { this.dateTo = dateTo; }

    public String getFilters() { return filters; }
    public void setFilters(String filters) { this.filters = filters; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getExpiredAt() { return expiredAt; }
    public void setExpiredAt(LocalDateTime expiredAt) { this.expiredAt = expiredAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}