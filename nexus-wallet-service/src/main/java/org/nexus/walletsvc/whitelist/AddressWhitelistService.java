package org.nexus.walletsvc.whitelist;

/**
 * 地址白名单服务接口（钱包管理服务侧）。
 *
 * <p>原实现位于 {@code org.nexus.wallet.wallet.whitelist.AddressWhitelistService}（exchange-wallet），
 * 本接口为独立部署后的服务边界抽象。</p>
 *
 * <p>PoC 阶段：仅定义接口边界，实际白名单逻辑仍由 exchange-wallet 进程内提供。</p>
 */
public interface AddressWhitelistService {

    /**
     * 判断地址是否已加白。
     *
     * @param address 钱包地址
     * @return {@code true} 表示已加白
     */
    boolean isWhitelisted(String address);

    /**
     * 加入白名单。
     *
     * @param address 钱包地址
     * @return 操作是否成功
     */
    boolean add(String address);

    /**
     * 移出白名单。
     *
     * @param address 钱包地址
     * @return 操作是否成功
     */
    boolean remove(String address);
}