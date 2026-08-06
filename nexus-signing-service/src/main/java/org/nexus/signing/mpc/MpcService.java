package org.nexus.signing.mpc;

/**
 * MPC（多方计算）签名服务接口。
 *
 * <p>定义签名服务对 MPC 阈值签名流程的服务边界。原实现位于
 * {@code org.nexus.wallet.signing.mpc.MpcService}（exchange-wallet），
 * 本接口为独立部署后的服务边界抽象。</p>
 *
 * <p>PoC 阶段：仅定义接口边界，实际 MPC 流程仍由 exchange-wallet 进程内提供。
 * 完整迁移涉及 mpc 子包（barrier / persistence / router / security / transport / wal）
 * 及 ColdWalletMultiSigService / MpcSigner / MpcSignatureAggregator 等组件，
 * 见 README.md 迁移计划。</p>
 */
public interface MpcService {

    /**
     * 判断指定金额的提现是否可通过 MPC 流程签名。
     *
     * @param amount 提现金额
     * @return {@code true} 表示当前在线参与者满足阈值且金额属于冷钱包层级
     */
    boolean canSign(java.math.BigDecimal amount);

    /**
     * 获取当前 MPC 池的阈值。
     *
     * @return 阈值（t of n）
     */
    int getThreshold();

    /**
     * 获取当前 MPC 池的总参与者数。
     *
     * @return 总参与者数（n）
     */
    int getTotalParticipants();
}