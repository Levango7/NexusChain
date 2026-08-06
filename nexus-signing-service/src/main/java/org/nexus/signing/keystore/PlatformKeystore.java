package org.nexus.signing.keystore;

/**
 * 平台密钥库接口（签名服务侧）。
 *
 * <p>定义签名服务对平台热钱包密钥的访问边界。原实现位于
 * {@code org.nexus.wallet.signing.keystore.PlatformKeystore}（exchange-wallet），
 * 本接口为独立部署后的服务边界抽象，未来完整迁移时由
 * {@code DefaultPlatformKeystore} 实现并从配置加载密钥。</p>
 *
 * <p>PoC 阶段：仅定义接口边界，实际密钥访问仍由 exchange-wallet 进程内提供。</p>
 */
public interface PlatformKeystore {

    /**
     * 获取平台私钥（hex）。
     *
     * @return 平台私钥 hex 字符串，未加载时返回 {@code null}
     */
    String getPrikey();

    /**
     * 获取平台公钥（hex）。
     *
     * @return 平台公钥 hex 字符串，未加载时返回 {@code null}
     */
    String getPubkey();

    /**
     * 判断密钥库是否已成功加载。
     *
     * @return {@code true} 表示私钥与公钥均已就绪
     */
    boolean isLoaded();
}