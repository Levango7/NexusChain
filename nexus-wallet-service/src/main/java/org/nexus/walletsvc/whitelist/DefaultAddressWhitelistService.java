package org.nexus.walletsvc.whitelist;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * {@link AddressWhitelistService} 的默认骨架实现。
 *
 * <p>PoC 阶段：使用内存 Set 维护白名单，仅用于保证模块可独立编译。
 * 完整迁移后将接入 DefaultAddressWhitelistService 持久化逻辑。</p>
 */
@Service
public class DefaultAddressWhitelistService implements AddressWhitelistService {

    private final Set<String> whitelist = new CopyOnWriteArraySet<>();

    @Override
    public boolean isWhitelisted(String address) {
        return address != null && whitelist.contains(address);
    }

    @Override
    public boolean add(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        return whitelist.add(address);
    }

    @Override
    public boolean remove(String address) {
        if (address == null) {
            return false;
        }
        return whitelist.remove(address);
    }
}