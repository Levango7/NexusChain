package org.nexus.bridge.liquidity;

import java.math.BigDecimal;

/**
 * 流动性管理接口。
 *
 * <p>定义跨链桥侧的流动性查询、注入、抽取与再平衡能力。</p>
 *
 * @since 1.2
 */
public interface LiquidityManager {

    /**
     * 查询指定链的流动性池。
     *
     * @param chainId 链 ID
     * @return 流动性池
     */
    LiquidityPool getLiquidity(String chainId);

    /**
     * 向指定链注入流动性。
     *
     * @param chainId 链 ID
     * @param amount  注入金额
     */
    void addLiquidity(String chainId, BigDecimal amount);

    /**
     * 从指定链抽取流动性。
     *
     * @param chainId 链 ID
     * @param amount  抽取金额
     */
    void removeLiquidity(String chainId, BigDecimal amount);

    /**
     * 跨所有链执行流动性再平衡，使利用率趋近目标值。
     */
    void rebalance();
}