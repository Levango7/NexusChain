package org.nexus.compliance.kyc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * KYC 申请实体。
 * <p>
 * 描述一次 KYC 申请的用户、证件信息与审核状态。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KycApplication {

    /** 申请 ID */
    @JsonProperty("applicationId")
    private String applicationId;

    /** 用户 ID */
    @JsonProperty("userId")
    private String userId;

    /** 证件类型 */
    @JsonProperty("idType")
    private String idType;

    /** 证件号 */
    @JsonProperty("idNumber")
    private String idNumber;

    /** 证件图片 URL */
    @JsonProperty("idImageUrl")
    private String idImageUrl;

    /** 申请状态 */
    @JsonProperty("status")
    private ApplicationStatus status;

    /** 提交时间 */
    @JsonProperty("submittedAt")
    private Instant submittedAt;

    /** 申请状态枚举 */
    public enum ApplicationStatus {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED
    }

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getIdType() { return idType; }
    public void setIdType(String idType) { this.idType = idType; }

    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String idNumber) { this.idNumber = idNumber; }

    public String getIdImageUrl() { return idImageUrl; }
    public void setIdImageUrl(String idImageUrl) { this.idImageUrl = idImageUrl; }

    public ApplicationStatus getStatus() { return status; }
    public void setStatus(ApplicationStatus status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
}