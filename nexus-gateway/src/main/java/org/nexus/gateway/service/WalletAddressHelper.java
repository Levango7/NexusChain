package org.nexus.gateway.service;

import org.nexus.sdk.wallet.WalletUtils;
import org.springframework.stereotype.Component;

/**
 * WalletUtils.addressToPubkeyHash 静态方法的 Spring Bean 包装。
 *
 * <p>Spring Boot 4.0 升级修复：Mockito {@code MockedStatic} 在 {@code @SpringBootTest}
 * 集成测试中因 classloader 隔离无法拦截服务代码内的静态方法调用。通过此 bean 包装，
 * 测试可用 {@code @MockitoBean} 替换整个 bean，避免 {@code MockedStatic} 的限制。</p>
 *
 * <p>生产代码调用 {@link WalletUtils#addressToPubkeyHash(String)} 做本地 Base58→hash 计算，
 * 此 wrapper 仅做透传，不改变任何业务行为。</p>
 */
@Component
public class WalletAddressHelper {

    /**
     * 将 NEX Base58 地址转换为公钥哈希（hex）。
     *
     * @param address NEX 地址
     * @return 公钥哈希 hex 字符串，失败返回 {@code null}
     */
    public String addressToPubkeyHash(String address) {
        return WalletUtils.addressToPubkeyHash(address);
    }
}