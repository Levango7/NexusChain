package org.nexus.gateway.apiversion;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * API 版本策略实体。
 *
 * <p>定义每个 API 版本的生命周期状态与治理元数据，包括：</p>
 * <ul>
 *   <li>{@link VersionStatus#ACTIVE} — 正常服务，无限制</li>
 *   <li>{@link VersionStatus#DEPRECATED} — 已声明废弃，响应附加 Deprecation/Sunset/Link 头</li>
 *   <li>{@link VersionStatus#SUNSET} — 已过 Sunset 日期，返回 410 Gone + 迁移指南</li>
 *   <li>{@link VersionStatus#RETIRED} — 完全移除，返回 404 Not Found</li>
 * </ul>
 *
 * <p>配合 {@link ApiVersionDeprecationService} 实现自动化版本治理，
 * 包括 HTTP 头注入、请求拦截与事件通知。</p>
 */
@Entity
@Table(name = "api_version_policies")
public class ApiVersionPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 版本标签，如 "v1"、"v2" */
    @Column(name = "version", unique = true, nullable = false, length = 16)
    private String version;

    /** 版本状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private VersionStatus status = VersionStatus.ACTIVE;

    /** 废弃声明日期（进入 DEPRECATED 状态的日期） */
    @Column(name = "deprecated_at")
    private LocalDate deprecatedAt;

    /** 日落日期（此日期后返回 410 Gone） */
    @Column(name = "sunset_at")
    private LocalDate sunsetAt;

    /** 完全移除日期（进入 RETIRED 状态的日期） */
    @Column(name = "retired_at")
    private LocalDate retiredAt;

    /** 后继版本，如 v1 的后继是 v2 */
    @Column(name = "successor_version", length = 16)
    private String successorVersion;

    /** 迁移指南文本 */
    @Column(name = "migration_guide", length = 2048)
    private String migrationGuide;

    /** 描述信息 */
    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * API 版本状态枚举。
     */
    public enum VersionStatus {
        /** 正常服务 */
        ACTIVE,
        /** 已废弃——响应附加 Deprecation/Sunset/Link 头，但不阻断请求 */
        DEPRECATED,
        /** 已日落——Sunset 日期已过，返回 410 Gone */
        SUNSET,
        /** 已退役——完全移除，返回 404 Not Found */
        RETIRED
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // === Getters / Setters ===

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public VersionStatus getStatus() {
        return status;
    }

    public void setStatus(VersionStatus status) {
        this.status = status;
    }

    public LocalDate getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(LocalDate deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }

    public LocalDate getSunsetAt() {
        return sunsetAt;
    }

    public void setSunsetAt(LocalDate sunsetAt) {
        this.sunsetAt = sunsetAt;
    }

    public LocalDate getRetiredAt() {
        return retiredAt;
    }

    public void setRetiredAt(LocalDate retiredAt) {
        this.retiredAt = retiredAt;
    }

    public String getSuccessorVersion() {
        return successorVersion;
    }

    public void setSuccessorVersion(String successorVersion) {
        this.successorVersion = successorVersion;
    }

    public String getMigrationGuide() {
        return migrationGuide;
    }

    public void setMigrationGuide(String migrationGuide) {
        this.migrationGuide = migrationGuide;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}