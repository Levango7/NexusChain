package org.nexus.settlement.funds;

/**
 * 资金归集服务接口。
 * <p>
 * 负责将分散资金按归集订单归集到指定地址，并支持自动归集与冷钱包转移。
 * </p>
 */
public interface FundSweepService {

    /**
     * 执行单笔归集。
     *
     * @param order 归集订单
     * @return 处理后的归集订单
     */
    CollectionOrder sweep(CollectionOrder order);

    /**
     * 自动归集：扫描所有待归集资金并批量执行。
     *
     * @return 本次自动归集处理的订单数
     */
    int autoSweep();

    /**
     * 转移至冷钱包。
     *
     * @return 转移的订单数
     */
    int transferToCold();
}