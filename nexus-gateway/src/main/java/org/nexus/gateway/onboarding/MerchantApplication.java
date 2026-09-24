package org.nexus.gateway.onboarding;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 商户入驻申请实体 — JPA 映射 merchant_applications 表。
 *
 * <p>记录商户从提交入驻申请到审核完成的完整生命周期。
 * 审核通过后自动触发开通流程：创建商户账号、生成 API Key、
 * 配置默认支付渠道与结算周期。</p>
 *
 * <p>状态流转：{@code PENDING} → {@code REVIEWING} → {@code APPROVED} / {@code REJECTED}。
 * 已通过的申请可被 {@code SUSPENDED}（暂停服务）。</p>
 */
@Entity
@Table(name = "merchant_applications",
        indexes = {
                @Index(name = "idx_ma_status", columnList = "status"),
                @Index(name = "idx_ma_business_license", columnList = "business_license_no"),
                @Index(name = "idx_ma_contact_email", columnList = "contact_email")
        })
public class MerchantApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户名称（公司全称）。 */
    @Column(name = "merchant_name", nullable = false, length = 128)
    private String merchantName;

    /** 联系人姓名。 */
    @Column(name = "contact_name", nullable = false, length = 64)
    private String contactName;

    /** 联系人邮箱。 */
    @Column(name = "contact_email", nullable = false, length = 128)
    private String contactEmail;

    /** 联系人电话。 */
    @Column(name = "contact_phone", nullable = false, length = 32)
    private String contactPhone;

    /** 业务类型：ECOMMERCE / DIGITAL_SERVICES / PHYSICAL_RETAIL / SERVICES / OTHER。 */
    @Column(name = "business_type", nullable = false, length = 32)
    private String businessType;

    /** 营业执照编号。 */
    @Column(name = "business_license_no", nullable = false, length = 64)
    private String businessLicenseNo;

    /** 营业执照文件 URL。 */
    @Column(name = "business_license_url", length = 512)
    private String businessLicenseUrl;

    /** 商户网站 URL。 */
    @Column(name = "website_url", length = 512)
    private String websiteUrl;

    /** 商户描述（业务简介）。 */
    @Column(name = "description", length = 1024)
    private String description;

    /** 申请状态：PENDING / REVIEWING / APPROVED / REJECTED / SUSPENDED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApplicationStatus status = ApplicationStatus.PENDING;

    /** 申请提交时间。 */
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    /** 审核开始时间（审核人员领取时设置）。 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 审核人员 ID。 */
    @Column(name = "reviewer_id", length = 64)
    private String reviewerId;

    /** 审核意见（通过/拒绝原因）。 */
    @Column(name = "review_comment", length = 1024)
    private String reviewComment;

    /** 审核通过时间（触发自动开通流程的时间）。 */
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /** 关联的商户 ID（审核通过后创建商户账号时设置）。 */
    @Column(name = "merchant_id")
    private Long merchantId;

    @PrePersist
    protected void onCreate() {
        this.submittedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = ApplicationStatus.PENDING;
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public String getBusinessLicenseNo() { return businessLicenseNo; }
    public void setBusinessLicenseNo(String businessLicenseNo) { this.businessLicenseNo = businessLicenseNo; }

    public String getBusinessLicenseUrl() { return businessLicenseUrl; }
    public void setBusinessLicenseUrl(String businessLicenseUrl) { this.businessLicenseUrl = businessLicenseUrl; }

    public String getWebsiteUrl() { return websiteUrl; }
    public void setWebsiteUrl(String websiteUrl) { this.websiteUrl = websiteUrl; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }

    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }

    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
}