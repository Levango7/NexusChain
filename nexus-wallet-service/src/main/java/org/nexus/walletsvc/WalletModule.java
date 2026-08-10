package org.nexus.walletsvc;

/**
 * 钱包管理服务模块标识与版本信息。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」新建模块。本类作为模块入口标识，
 * 实际启动类在未来完整迁移时由 WalletServiceApplication 提供。</p>
 *
 * <p>包名使用 {@code org.nexus.walletsvc} 而非 {@code org.nexus.wallet}，
 * 以避免与现有 exchange-wallet 的 {@code org.nexus.wallet.*} 包冲突，
 * 便于迁移过程中两套代码并存。</p>
 */
public final class WalletModule {

    /** 模块名称 */
    public static final String MODULE_NAME = "nexus-wallet-service";

    /** 模块版本 */
    public static final String MODULE_VERSION = "1.2.0";

    /** 服务在 Nacos 中注册的默认服务名 */
    public static final String DEFAULT_SERVICE_NAME = "nexus-wallet-service";

    private WalletModule() {
    }
}