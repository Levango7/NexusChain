package org.nexus.gateway.security.replay;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 防重放拦截统计实体。映射 {@code replay_interception_stats} 表。
 *
 * <p>每次防重放拦截（时间戳过期、nonce 重放、nonce 过短、幂等键无效等）
 * 记录一条统计记录，用于运营监控和告警分析。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §3.2 V64、§4.2.2。</p>
 */
@Entity
@Table(name = "replay_interception_stats")
public class ReplayInterceptionStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    /** 错误码：40103/40106/40109/40110/40111/40112 等。 */
    @Column(name = "error_code", nullable = false, length = 32)
    private String errorCode;

    @Column(name = "intercepted_at", nullable = false)
    private LocalDateTime interceptedAt;

    @PrePersist
    protected void onCreate() {
        if (this.interceptedAt == null) {
            this.interceptedAt = LocalDateTime.now();
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public LocalDateTime getInterceptedAt() { return interceptedAt; }
    public void setInterceptedAt(LocalDateTime interceptedAt) { this.interceptedAt = interceptedAt; }
}