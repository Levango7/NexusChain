package org.nexus.walletsvc.custody;

import org.springframework.stereotype.Service;

/**
 * {@link CustodyService} 的默认骨架实现。
 *
 * <p>PoC 阶段：所有钱包报告为热托管（非冷托管），仅用于保证模块可独立编译。
 * 完整迁移后将接入 DefaultCustodyService 真实逻辑。</p>
 */
@Service
public class DefaultCustodyService implements CustodyService {

    @Override
    public boolean isColdCustody(String walletId) {
        return false;
    }

    @Override
    public String getCustodyTier(String walletId) {
        return "HOT";
    }
}