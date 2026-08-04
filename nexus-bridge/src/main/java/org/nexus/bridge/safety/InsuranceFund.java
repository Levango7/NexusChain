package org.nexus.bridge.safety;

import java.math.BigDecimal;

/**
 * 保险基金接口。
 *
 * <p>用于在跨链资产损失事件中对受害者进行补偿。</p>
 *
 * @since 1.2
 */
public interface InsuranceFund {

    /**
     * 向保险基金存入资金。
     *
     * @param amount 存入金额
     */
    void deposit(BigDecimal amount);

    /**
     * 对受害者执行补偿。
     *
     * @param victimId 受害者 ID
     * @param amount   补偿金额
     * @param reason   补偿原因
     */
    void compensate(String victimId, BigDecimal amount, String reason);

    /**
     * 查询保险基金当前余额。
     *
     * @return 余额
     */
    BigDecimal getBalance();
}