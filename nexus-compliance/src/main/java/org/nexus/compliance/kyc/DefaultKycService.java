package org.nexus.compliance.kyc;

import org.springframework.stereotype.Service;

/**
 * 默认 KYC 服务骨架实现。
 * <p>
 * 当前为空实现占位，所有方法体留待后续业务逻辑填充。
 * </p>
 */
@Service
public class DefaultKycService implements KycService {

    @Override
    public KycApplication submitKyc(KycApplication application) {
        // TODO: 实现申请受理逻辑（去重校验 → 落库 → 触发审核流程）
        return application;
    }

    @Override
    public KycApplication reviewKyc(String applicationId) {
        // TODO: 实现审核逻辑（人工/自动审核 → 更新状态 → 通知用户）
        return new KycApplication();
    }

    @Override
    public KycLevel getKycStatus(String userId) {
        // TODO: 实现状态查询逻辑（按用户聚合最新有效申请 → 映射为等级）
        return KycLevel.NONE;
    }
}