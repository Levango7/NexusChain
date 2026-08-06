package org.nexus.signing.mpc;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link MpcService} 的默认骨架实现。
 *
 * <p>PoC 阶段：返回固定阈值（3 of 5），所有金额均报告不可签名，
 * 仅用于保证签名服务模块可独立编译与装配。完整迁移后将接入
 * ColdWalletMultiSigService / MpcApprovalPolicy 等真实 MPC 组件。</p>
 */
@Component
public class DefaultMpcService implements MpcService {

    /** 默认冷钱包 MPC 阈值（与 exchange-wallet MpcApprovalPolicy 保持一致） */
    private static final int DEFAULT_THRESHOLD = 3;

    /** 默认冷钱包 MPC 总参与者数 */
    private static final int DEFAULT_TOTAL = 5;

    @Override
    public boolean canSign(BigDecimal amount) {
        // PoC：始终返回 false，实际逻辑待迁移
        return false;
    }

    @Override
    public int getThreshold() {
        return DEFAULT_THRESHOLD;
    }

    @Override
    public int getTotalParticipants() {
        return DEFAULT_TOTAL;
    }
}