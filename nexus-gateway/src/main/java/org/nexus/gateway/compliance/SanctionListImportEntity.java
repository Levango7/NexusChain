package org.nexus.gateway.compliance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 制裁名单导入记录（{@code sanction_list_imports}，2026-10-12 新增）。
 *
 * <p>回答合规尽调的那句问话：<b>"当前生效的名单是哪个版本、从哪来、谁导的、有多少条"</b>。
 * append-only：业务上只 INSERT，不提供改/删接口（与 {@code audit_events} 同口径）。</p>
 *
 * <p>字段与迁移 {@code V97__sanction_list_imports.sql} 一一对应；
 * 实体放在网关模块（与 {@code AuditEventEntity} 同处），因为名单文件与端点都归网关壳层，
 * 而 {@code nexus-compliance} 是库模块（不自带数据源）。</p>
 */
@Entity
@Table(name = "sanction_list_imports")
public class SanctionListImportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    @Column(name = "actor", length = 128)
    private String actor;

    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "version", length = 64)
    private String version;

    @Column(name = "format", nullable = false, length = 16)
    private String format;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "imported_count", nullable = false)
    private Integer importedCount;

    @Column(name = "skipped_count", nullable = false)
    private Integer skippedCount;

    @Column(name = "removed_count", nullable = false)
    private Integer removedCount;

    @Column(name = "total_after", nullable = false)
    private Integer totalAfter;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @PrePersist
    void prePersist() {
        if (importedAt == null) {
            importedAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Integer getImportedCount() { return importedCount; }
    public void setImportedCount(Integer importedCount) { this.importedCount = importedCount; }

    public Integer getSkippedCount() { return skippedCount; }
    public void setSkippedCount(Integer skippedCount) { this.skippedCount = skippedCount; }

    public Integer getRemovedCount() { return removedCount; }
    public void setRemovedCount(Integer removedCount) { this.removedCount = removedCount; }
    public Integer getTotalAfter() { return totalAfter; }
    public void setTotalAfter(Integer totalAfter) { this.totalAfter = totalAfter; }

    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
}
