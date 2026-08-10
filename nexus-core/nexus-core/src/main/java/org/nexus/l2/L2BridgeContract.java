package org.nexus.l2;

import java.math.BigInteger;

/**
 * L1↔L2 桥合约接口。
 *
 * <p>定义一层与二层之间的资产跨层转移与状态根提交能力。
 * 存款从 L1 锁定资产并在 L2 铸造；提款从 L2 销毁并在 L1 释放。</p>
 *
 * @since 1.2
 */
public interface L2BridgeContract {

    /**
     * 从 L1 存款到 L2。
     *
     * @param from   存款人地址
     * @param amount 存款金额
     * @return 存款操作 ID
     */
    String deposit(String from, BigInteger amount);

    /**
     * 从 L2 提款到 L1。
     *
     * @param to     收款人地址
     * @param amount 提款金额
     * @return 提款操作 ID
     */
    String withdraw(String to, BigInteger amount);

    /**
     * 提交 L2 状态根到 L1。
     *
     * @param batchId   批次 ID
     * @param stateRoot 状态根
     * @return 提交成功返回 true
     */
    boolean submitStateRoot(long batchId, String stateRoot);

    /**
     * 查询存款状态。
     *
     * @param depositId 存款操作 ID
     * @return 存款状态
     */
    L2TransactionStatus getDepositStatus(String depositId);

    /**
     * 查询提款状态。
     *
     * @param withdrawId 提款操作 ID
     * @return 提款状态
     */
    L2TransactionStatus getWithdrawStatus(String withdrawId);
}