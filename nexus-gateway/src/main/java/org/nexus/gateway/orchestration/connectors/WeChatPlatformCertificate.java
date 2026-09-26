package org.nexus.gateway.orchestration.connectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 微信支付平台证书缓存实体 — 持久化存储微信平台证书，支持自动轮换与过期检测。
 *
 * <p>微信平台证书有效期通常为 12 个月，通过 GET /v3/certificates 接口获取。
 * 本实体将证书持久化到数据库，避免重启后丢失缓存。</p>
 */
@Entity
@Table(name = "wechat_platform_certificates")
public class WeChatPlatformCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "serial_no", nullable = false, length = 128, unique = true)
    private String serialNo;

    @Column(name = "certificate_content", nullable = false, columnDefinition = "TEXT")
    private String certificateContent;

    @Column(name = "effective_time", nullable = false)
    private Instant effectiveTime;

    @Column(name = "expire_time", nullable = false)
    private Instant expireTime;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CertificateStatus status;

    public WeChatPlatformCertificate() {
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSerialNo() { return serialNo; }
    public void setSerialNo(String serialNo) { this.serialNo = serialNo; }

    public String getCertificateContent() { return certificateContent; }
    public void setCertificateContent(String certificateContent) { this.certificateContent = certificateContent; }

    public Instant getEffectiveTime() { return effectiveTime; }
    public void setEffectiveTime(Instant effectiveTime) { this.effectiveTime = effectiveTime; }

    public Instant getExpireTime() { return expireTime; }
    public void setExpireTime(Instant expireTime) { this.expireTime = expireTime; }

    public Instant getFetchedAt() { return fetchedAt; }
    public void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }

    public CertificateStatus getStatus() { return status; }
    public void setStatus(CertificateStatus status) { this.status = status; }
}