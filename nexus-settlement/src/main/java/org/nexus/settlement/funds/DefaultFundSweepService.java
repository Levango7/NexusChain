package org.nexus.settlement.funds;

import org.springframework.stereotype.Service;

/**
 * 默认资金归集服务骨架实现。
 * <p>
 * 当前为空实现占位，所有方法体留待后续业务逻辑填充。
 * </p>
 */
@Service
public class DefaultFundSweepService implements FundSweepService {

    @Override
    public CollectionOrder sweep(CollectionOrder order) {
        // TODO: 实现单笔归集逻辑（构造链上转账 → 等待确认 → 更新状态）
        return order;
    }

    @Override
    public int autoSweep() {
        // TODO: 实现自动归集逻辑（扫描待归集地址 → 生成订单 → 批量执行）
        return 0;
    }

    @Override
    public int transferToCold() {
        // TODO: 实现冷钱包转移逻辑（热钱包余额阈值判断 → 转账至冷钱包 → 审计落账）
        return 0;
    }
}