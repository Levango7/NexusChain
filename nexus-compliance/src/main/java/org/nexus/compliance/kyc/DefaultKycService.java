package org.nexus.compliance.kyc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认 KYC 服务实现。
 * <p>
 * 受理逻辑：
 * <ul>
 *   <li>{@link #submitKyc}：校验必填项（userId / idType / idNumber）→ 同一用户重复申请
 *       且已有 PENDING 申请时拒绝 → 分配申请 ID、置 PENDING、落库</li>
 *   <li>{@link #reviewKyc}：按证件要素完整度自动审核——证件图片缺失则 REJECTED，
 *       否则 APPROVED；审核结果即时可查</li>
 *   <li>{@link #getKycStatus}：按用户聚合最新 APPROVED 申请并映射为等级
 *       （含证件图片 → ENHANCED，机构证件 → INSTITUTIONAL，其余 → BASIC）</li>
 * </ul>
 * 当前为进程内存储，后续替换为持久化存储时仅需替换存储层。
 * </p>
 */
@Service
public class DefaultKycService implements KycService {

    private static final Logger log = LoggerFactory.getLogger(DefaultKycService.class);

    /** 机构证件类型标识 */
    private static final String INSTITUTIONAL_ID_TYPE = "INSTITUTIONAL";

    /** 申请表（applicationId → application） */
    private final Map<String, KycApplication> applications = new ConcurrentHashMap<>();

    @Override
    public KycApplication submitKyc(KycApplication application) {
        if (application == null) {
            throw new IllegalArgumentException("KYC application must not be null");
        }
        if (isBlank(application.getUserId())) {
            throw new IllegalArgumentException("userId is required");
        }
        if (isBlank(application.getIdType()) || isBlank(application.getIdNumber())) {
            throw new IllegalArgumentException("idType and idNumber are required");
        }

        // 去重：同一用户已有 PENDING 申请时拒绝
        boolean hasPending = applications.values().stream()
                .anyMatch(app -> application.getUserId().equals(app.getUserId())
                        && app.getStatus() == KycApplication.ApplicationStatus.PENDING);
        if (hasPending) {
            throw new IllegalStateException("User already has a pending KYC application");
        }

        if (isBlank(application.getApplicationId())) {
            application.setApplicationId("KYC-" + UUID.randomUUID().toString().replace("-", ""));
        }
        application.setStatus(KycApplication.ApplicationStatus.PENDING);
        if (application.getSubmittedAt() == null) {
            application.setSubmittedAt(Instant.now());
        }
        applications.put(application.getApplicationId(), application);
        log.info("KYC application submitted: applicationId={}, userId={}",
                application.getApplicationId(), application.getUserId());
        return application;
    }

    @Override
    public KycApplication reviewKyc(String applicationId) {
        if (isBlank(applicationId)) {
            throw new IllegalArgumentException("applicationId is required");
        }
        KycApplication application = applications.get(applicationId);
        if (application == null) {
            throw new IllegalArgumentException("KYC application not found: " + applicationId);
        }
        if (application.getStatus() != KycApplication.ApplicationStatus.PENDING) {
            throw new IllegalStateException("KYC application is not pending: " + application.getStatus());
        }

        // 自动审核：证件图片缺失视为材料不全，拒绝
        if (isBlank(application.getIdImageUrl())) {
            application.setStatus(KycApplication.ApplicationStatus.REJECTED);
            log.info("KYC application rejected (missing id image): applicationId={}", applicationId);
        } else {
            application.setStatus(KycApplication.ApplicationStatus.APPROVED);
            log.info("KYC application approved: applicationId={}, userId={}",
                    applicationId, application.getUserId());
        }
        return application;
    }

    @Override
    public KycLevel getKycStatus(String userId) {
        if (isBlank(userId)) {
            return KycLevel.NONE;
        }
        // 取该用户最新一条 APPROVED 申请
        KycApplication latestApproved = applications.values().stream()
                .filter(app -> userId.equals(app.getUserId()))
                .filter(app -> app.getStatus() == KycApplication.ApplicationStatus.APPROVED)
                .max((a, b) -> {
                    Instant ta = a.getSubmittedAt() != null ? a.getSubmittedAt() : Instant.EPOCH;
                    Instant tb = b.getSubmittedAt() != null ? b.getSubmittedAt() : Instant.EPOCH;
                    return ta.compareTo(tb);
                })
                .orElse(null);

        if (latestApproved == null) {
            return KycLevel.NONE;
        }
        if (INSTITUTIONAL_ID_TYPE.equalsIgnoreCase(latestApproved.getIdType())) {
            return KycLevel.INSTITUTIONAL;
        }
        if (!isBlank(latestApproved.getIdImageUrl())) {
            return KycLevel.ENHANCED;
        }
        return KycLevel.BASIC;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
