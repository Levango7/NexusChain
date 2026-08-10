package org.nexus.sdk.client;

import java.math.BigDecimal;

/**
 * {@link WalletMgmtClient} 的进程内骨架实现。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」引入。当前阶段所有方法返回占位结果，
 * 不执行实际钱包管理操作。实际进程内委托由 nexus-gateway 中的适配器
 * （继承或包装本类）完成，将调用转发至 exchange-wallet 进程内服务。</p>
 *
 * <p>本类提供「无依赖」的默认实现，保证 nexus-sdk 可独立编译。
 * gateway 端通过 Spring {@code @Primary} 或 {@code @Configuration} 装配
 * 实际委托实现覆盖本骨架。</p>
 */
public class InProcessWalletMgmtClient implements WalletMgmtClient {

    /**
     * 默认构造器。
     *
     * <p>子类可重写所有方法以提供实际委托逻辑。</p>
     */
    public InProcessWalletMgmtClient() {
    }

    @Override
    public String addressToPubkeyHash(String address) {
        // PoC 骨架：返回 null，实际逻辑由 gateway 适配器覆盖
        return null;
    }

    @Override
    public boolean verifyAddress(String address) {
        // PoC 骨架：返回 false，实际逻辑由 gateway 适配器覆盖
        return false;
    }

    @Override
    public boolean isAddressWhitelisted(String address) {
        // PoC 骨架：返回 false，实际逻辑由 gateway 适配器覆盖
        return false;
    }

    @Override
    public String getCustodyTier(String walletId) {
        // PoC 骨架：返回 HOT，实际逻辑由 gateway 适配器覆盖
        return "HOT";
    }
}