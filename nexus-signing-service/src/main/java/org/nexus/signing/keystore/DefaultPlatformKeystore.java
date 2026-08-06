package org.nexus.signing.keystore;

import org.springframework.stereotype.Component;

/**
 * {@link PlatformKeystore} 的默认骨架实现。
 *
 * <p>PoC 阶段：返回未加载状态，仅用于保证签名服务模块可独立编译与装配。
 * 完整迁移后将从配置加载平台密钥库（参考 exchange-wallet 的
 * {@code org.nexus.wallet.signing.keystore.PlatformKeystore}）。</p>
 */
@Component
public class DefaultPlatformKeystore implements PlatformKeystore {

    @Override
    public String getPrikey() {
        return null;
    }

    @Override
    public String getPubkey() {
        return null;
    }

    @Override
    public boolean isLoaded() {
        return false;
    }
}