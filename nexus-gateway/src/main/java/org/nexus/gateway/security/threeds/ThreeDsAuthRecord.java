package org.nexus.gateway.security.threeds;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 3DS 认证记录实体，映射 {@code three_ds_auth_records} 表。
 *
 * <p>记录一次 3DS 认证流程的完整生命周期数据，包括风险评估分数、
 * ACS 返回的交易状态、Frictionless/Challenge 流程标识、以及错误信息。</p>
 */
@Entity
@Table(name = "three_ds_auth_records")
public class ThreeDsAuthRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户 ID（数据隔离键）。 */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 商户 ID。 */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 关联支付订单 ID。 */
    @Column(name = "payment_order_id", nullable = false)
    private Long paymentOrderId;

    /** 认证状态：INITIATED / COMPLETED / FAILED / TIMEOUT。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_status", nullable = false, length = 32)
    private AuthStatus authStatus = AuthStatus.INITIATED;

    /** 交易状态：Y / C / N / R / U（EMVCo 3DS 规范）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "trans_status", nullable = false, length = 16)
    private TransStatus transStatus;

    /** 3DS 消息版本，默认 2.2.0。 */
    @Column(name = "message_version", nullable = false, length = 16)
    private String messageVersion = "2.2.0";

    /** ACS 交易 ID（模拟 ACS 分配）。 */
    @Column(name = "acs_trans_id", length = 128)
    private String acsTransId;

    /** DS 交易 ID（模拟 DS 分配）。 */
    @Column(name = "ds_trans_id", length = 128)
    private String dsTransId;

    /** ACS Challenge URL（Challenge 流程时生成）。 */
    @Column(name = "acs_challenge_url", length = 512)
    private String acsChallengeUrl;

    /** 风险评分（0-100）。 */
    @Column(name = "risk_score")
    private Integer riskScore;

    /** 是否 Frictionless 流程。 */
    @Column(name = "frictionless_flow", nullable = false)
    private boolean frictionlessFlow = false;

    /** 是否 Challenge 流程。 */
    @Column(name = "challenge_flow", nullable = false)
    private boolean challengeFlow = false;

    /** 认证发起时间。 */
    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    /** 认证完成时间。 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 错误码（认证失败或超时时填写）。 */
    @Column(name = "error_code", length = 32)
    private String errorCode;

    /** 错误详情。 */
    @Column(name = "error_detail", length = 512)
    private String errorDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Long getPaymentOrderId() { return paymentOrderId; }
    public void setPaymentOrderId(Long paymentOrderId) { this.paymentOrderId = paymentOrderId; }

    public AuthStatus getAuthStatus() { return authStatus; }
    public void setAuthStatus(AuthStatus authStatus) { this.authStatus = authStatus; }

    public TransStatus getTransStatus() { return transStatus; }
    public void setTransStatus(TransStatus transStatus) { this.transStatus = transStatus; }

    public String getMessageVersion() { return messageVersion; }
    public void setMessageVersion(String messageVersion) { this.messageVersion = messageVersion; }

    public String getAcsTransId() { return acsTransId; }
    public void setAcsTransId(String acsTransId) { this.acsTransId = acsTransId; }

    public String getDsTransId() { return dsTransId; }
    public void setDsTransId(String dsTransId) { this.dsTransId = dsTransId; }

    public String getAcsChallengeUrl() { return acsChallengeUrl; }
    public void setAcsChallengeUrl(String acsChallengeUrl) { this.acsChallengeUrl = acsChallengeUrl; }

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public boolean isFrictionlessFlow() { return frictionlessFlow; }
    public void setFrictionlessFlow(boolean frictionlessFlow) { this.frictionlessFlow = frictionlessFlow; }

    public boolean isChallengeFlow() { return challengeFlow; }
    public void setChallengeFlow(boolean challengeFlow) { this.challengeFlow = challengeFlow; }

    public LocalDateTime getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(LocalDateTime initiatedAt) { this.initiatedAt = initiatedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}