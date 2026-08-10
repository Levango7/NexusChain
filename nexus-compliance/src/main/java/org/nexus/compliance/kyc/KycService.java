package org.nexus.compliance.kyc;

/**
 * KYC 服务接口。
 * <p>
 * 负责用户 KYC 申请的提交、审核与状态查询。
 * </p>
 */
public interface KycService {

    /**
     * 提交 KYC 申请。
     *
     * @param application KYC 申请
     * @return 受理后的申请
     */
    KycApplication submitKyc(KycApplication application);

    /**
     * 审核 KYC 申请。
     *
     * @param applicationId 申请 ID
     * @return 审核后的申请
     */
    KycApplication reviewKyc(String applicationId);

    /**
     * 查询用户 KYC 状态。
     *
     * @param userId 用户 ID
     * @return KYC 等级
     */
    KycLevel getKycStatus(String userId);
}