package org.nexus.l2.gas;

import java.math.BigInteger;

/**
 * Gas 成本估算结果。
 *
 * <p>记录 L2 交易/批次的 gas 成本细分与 L1 settlement 摊销：</p>
 * <ul>
 *   <li>{@code executionGas}：L2 执行 gas（交易在 L2 VM 中执行消耗）</li>
 *   <li>{@code calldataGas}：L1 calldata gas（交易编码为 calldata 提交到 L1 的 gas）</li>
 *   <li>{@code blobGas}：L1 blob gas（EIP-4844 blob 携带时的 gas，每 blob 131072）</li>
 *   <li>{@code l1VerificationGas}：L1 验证固定开销（欺诈证明验证、状态根写入等）</li>
 *   <li>{@code l1SettlementCostWei}：L1 settlement 总成本（wei）</li>
 *   <li>{@code perTxFeeWei}：单笔 tx 摊销费用（wei）= l1SettlementCostWei / batchSize + l2ExecutionCost</li>
 * </ul>
 *
 * <p>供排序器/打包器决策（是否启用 blob、批次大小优化）与用户 fee 预估。</p>
 *
 * @since 1.3
 */
public final class GasCostEstimate {

    /** L2 执行 gas */
    private final long executionGas;

    /** L1 calldata gas */
    private final long calldataGas;

    /** L1 blob gas（EIP-4844） */
    private final long blobGas;

    /** L1 验证固定开销 gas */
    private final long l1VerificationGas;

    /** L1 settlement 总成本（wei） */
    private final BigInteger l1SettlementCostWei;

    /** 单笔 tx 摊销费用（wei） */
    private final BigInteger perTxFeeWei;

    /** 是否使用 blob 携带 */
    private final boolean useBlob;

    /** 批次大小（用于摊销计算） */
    private final int batchSize;

    public GasCostEstimate(long executionGas, long calldataGas, long blobGas,
                           long l1VerificationGas, BigInteger l1SettlementCostWei,
                           BigInteger perTxFeeWei, boolean useBlob, int batchSize) {
        this.executionGas = executionGas;
        this.calldataGas = calldataGas;
        this.blobGas = blobGas;
        this.l1VerificationGas = l1VerificationGas;
        this.l1SettlementCostWei = l1SettlementCostWei == null ? BigInteger.ZERO : l1SettlementCostWei;
        this.perTxFeeWei = perTxFeeWei == null ? BigInteger.ZERO : perTxFeeWei;
        this.useBlob = useBlob;
        this.batchSize = batchSize;
    }

    public long getExecutionGas() {
        return executionGas;
    }

    public long getCalldataGas() {
        return calldataGas;
    }

    public long getBlobGas() {
        return blobGas;
    }

    public long getL1VerificationGas() {
        return l1VerificationGas;
    }

    /**
     * 获取 L1 settlement 总 gas（calldata 或 blob + 验证开销）。
     *
     * @return L1 settlement 总 gas
     */
    public long getL1SettlementGas() {
        return (useBlob ? blobGas : calldataGas) + l1VerificationGas;
    }

    public BigInteger getL1SettlementCostWei() {
        return l1SettlementCostWei;
    }

    public BigInteger getPerTxFeeWei() {
        return perTxFeeWei;
    }

    public boolean isUseBlob() {
        return useBlob;
    }

    public int getBatchSize() {
        return batchSize;
    }

    /**
     * 获取总 gas（L2 执行 + L1 settlement）。
     *
     * @return 总 gas
     */
    public long getTotalGas() {
        return executionGas + getL1SettlementGas();
    }

    @Override
    public String toString() {
        return "GasCostEstimate{execGas=" + executionGas
                + ", calldataGas=" + calldataGas
                + ", blobGas=" + blobGas
                + ", l1VerifyGas=" + l1VerificationGas
                + ", l1SettlementWei=" + l1SettlementCostWei
                + ", perTxFeeWei=" + perTxFeeWei
                + ", useBlob=" + useBlob
                + ", batchSize=" + batchSize + "}";
    }
}