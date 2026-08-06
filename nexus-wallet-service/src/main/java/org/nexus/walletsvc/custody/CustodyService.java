package org.nexus.walletsvc.custody;

/**
 * 钱包托管策略接口（钱包管理服务侧）。
 *
 * <p>原实现位于 {@code org.nexus.wallet.wallet.custody.CustodyService}（exchange-wallet），
 * 本接口为独立部署后的服务边界抽象。</p>
 *
 * <p>PoC 阶段：仅定义接口边界，实际托管逻辑仍由 exchange-wallet 进程内提供。</p>
 */
public interface CustodyService {

    /**
     * 判断指定钱包是否处于冷托管。
     *
     * @param walletId 钱包 ID
     * @return {@code true} 表示冷托管
     */
    boolean isColdCustody(String walletId);

    /**
     * 获取指定钱包的托管层级。
     *
     * @param walletId 钱包 ID
     * @return 托管层级名称
     */
    String getCustodyTier(String walletId);
}