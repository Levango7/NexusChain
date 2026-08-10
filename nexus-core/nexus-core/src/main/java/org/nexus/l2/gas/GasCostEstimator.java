package org.nexus.l2.gas;

import org.nexus.l2.L2Transaction;
import org.nexus.l2.RollupBatch;
import org.nexus.l2.blob.BlobCarrierResult;
import org.nexus.l2.blob.BlobDataCarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.List;

/**
 * L2 Gas 成本估算器。
 *
 * <p>估算 L2 交易与批次的 gas 成本，包含三部分：</p>
 * <ol>
 *   <li><b>execution gas</b>：L2 VM 执行交易消耗的 gas（基于 tx 类型与 gasLimit）</li>
 *   <li><b>calldata gas</b>：交易编码为 L1 calldata 的 gas（非零字节 16，零字节 4）</li>
 *   <li><b>blob gas</b>：EIP-4844 blob 携带时的 gas（每 blob 131072）</li>
 * </ol>
 *
 * <p>L1 settlement 成本 = (calldata 或 blob) gas × 对应 base fee + L1 验证固定开销 gas × L1 base fee。
 * 单笔 tx 摊销费用 = L1 settlement 成本 / batchSize + L2 执行 gas × L2 gas price。</p>
 *
 * <p>供 {@code RollupBatcher} 决策批次大小与是否启用 blob 携带，
 * 供用户预估 fee 决定 priorityFee。</p>
 *
 * @since 1.3
 */
@Component
public class GasCostEstimator {

    private static final Logger logger = LoggerFactory.getLogger(GasCostEstimator.class);

    /** EIP-2028 calldata 非零字节 gas 成本 */
    public static final int CALLDATA_NON_ZERO_GAS = 16;

    /** EIP-2028 calldata 零字节 gas 成本 */
    public static final int CALLDATA_ZERO_GAS = 4;

    /** 默认 L2 执行 gas 单价（wei） */
    public static final BigInteger DEFAULT_L2_GAS_PRICE = BigInteger.ONE;

    /** 默认 L1 calldata base fee（wei per gas） */
    public static final BigInteger DEFAULT_L1_CALLDATA_BASE_FEE = new BigInteger("20");

    /** 默认 L1 验证固定开销 gas（状态根写入 + 桥合约调用） */
    public static final long DEFAULT_L1_VERIFICATION_GAS = 210_000L;

    /** 默认单笔 L2 tx 基础执行 gas（转账 21000，复杂合约调用估算 100000） */
    public static final long DEFAULT_TX_BASE_EXECUTION_GAS = 100_000L;

    /** L2 执行 gas 单价（wei） */
    private final BigInteger l2GasPrice;

    /** L1 calldata base fee（wei per gas） */
    private final BigInteger l1CalldataBaseFee;

    /** L1 验证固定开销 gas */
    private final long l1VerificationGas;

    /** 单笔 tx 基础执行 gas */
    private final long txBaseExecutionGas;

    /** Blob 数据携带器（用于查询 blob base fee） */
    @Autowired(required = false)
    private BlobDataCarrier blobDataCarrier;

    public GasCostEstimator() {
        this(DEFAULT_L2_GAS_PRICE, DEFAULT_L1_CALLDATA_BASE_FEE,
                DEFAULT_L1_VERIFICATION_GAS, DEFAULT_TX_BASE_EXECUTION_GAS);
    }

    public GasCostEstimator(BigInteger l2GasPrice, BigInteger l1CalldataBaseFee,
                            long l1VerificationGas, long txBaseExecutionGas) {
        this.l2GasPrice = l2GasPrice == null ? DEFAULT_L2_GAS_PRICE : l2GasPrice;
        this.l1CalldataBaseFee = l1CalldataBaseFee == null ? DEFAULT_L1_CALLDATA_BASE_FEE : l1CalldataBaseFee;
        this.l1VerificationGas = l1VerificationGas;
        this.txBaseExecutionGas = txBaseExecutionGas;
    }

    /**
     * 估算单笔 L2 交易的 gas 成本（不含 L1 settlement 摊销）。
     *
     * @param tx L2 交易
     * @return gas 成本估算
     */
    public GasCostEstimate estimateTxGas(L2Transaction tx) {
        if (tx == null) {
            return new GasCostEstimate(0, 0, 0, 0, BigInteger.ZERO, BigInteger.ZERO, false, 0);
        }
        long execGas = estimateExecutionGas(tx);
        long calldataGas = estimateCalldataGas(tx);
        long blobGas = BlobCarrierResult.BLOB_GAS_PER_BLOB;
        BigInteger l2ExecCost = BigInteger.valueOf(execGas).multiply(l2GasPrice);
        return new GasCostEstimate(
                execGas, calldataGas, blobGas, 0,
                BigInteger.ZERO, l2ExecCost, false, 1);
    }

    /**
     * 估算批次总 gas 成本与 L1 settlement 摊销。
     *
     * @param batch    批次
     * @param useBlob  是否使用 EIP-4844 blob 携带（true 用 blob gas，false 用 calldata gas）
     * @return gas 成本估算
     */
    public GasCostEstimate estimateBatchGas(RollupBatch batch, boolean useBlob) {
        if (batch == null) {
            return new GasCostEstimate(0, 0, 0, 0, BigInteger.ZERO, BigInteger.ZERO, useBlob, 0);
        }
        List<L2Transaction> txs = batch.getTransactions();
        int batchSize = txs == null ? 0 : txs.size();
        if (batchSize == 0) {
            return new GasCostEstimate(0, 0, 0, l1VerificationGas,
                    BigInteger.valueOf(l1VerificationGas).multiply(l1CalldataBaseFee),
                    BigInteger.ZERO, useBlob, 0);
        }

        long totalExecGas = 0;
        long totalCalldataGas = 0;
        for (L2Transaction tx : txs) {
            totalExecGas += estimateExecutionGas(tx);
            totalCalldataGas += estimateCalldataGas(tx);
        }

        // blob gas：每 blob 容纳 131072 字节，向上取整计算 blob 数
        long blobGas = 0;
        if (useBlob) {
            long totalBytes = estimateBatchDataBytes(txs);
            long blobCount = Math.max(1, (totalBytes + BlobCarrierResult.BYTES_PER_BLOB - 1)
                    / BlobCarrierResult.BYTES_PER_BLOB);
            blobGas = blobCount * BlobCarrierResult.BLOB_GAS_PER_BLOB;
        }

        // L1 settlement 成本
        BigInteger l1SettlementGas = BigInteger.valueOf(
                (useBlob ? blobGas : totalCalldataGas) + l1VerificationGas);
        BigInteger l1BaseFee = useBlob ? getL1BlobBaseFee() : l1CalldataBaseFee;
        BigInteger l1SettlementCost = l1SettlementGas.multiply(l1BaseFee);

        // 单笔 tx 摊销费用 = L1 settlement / batchSize + L2 执行 gas × L2 gas price / batchSize
        BigInteger perTxFee = l1SettlementCost.divide(BigInteger.valueOf(batchSize))
                .add(BigInteger.valueOf(totalExecGas).multiply(l2GasPrice)
                        .divide(BigInteger.valueOf(batchSize)));

        logger.debug("Batch gas estimate: size={}, execGas={}, calldataGas={}, blobGas={}, "
                        + "useBlob={}, l1SettlementWei={}, perTxFeeWei={}",
                batchSize, totalExecGas, totalCalldataGas, blobGas, useBlob, l1SettlementCost, perTxFee);

        return new GasCostEstimate(
                totalExecGas, totalCalldataGas, blobGas, l1VerificationGas,
                l1SettlementCost, perTxFee, useBlob, batchSize);
    }

    /**
     * 决策是否启用 blob 携带（基于成本对比）。
     *
     * <p>当 blob 携带成本 < calldata 携带成本时启用 blob。</p>
     *
     * @param batch 批次
     * @return 启用 blob 返回 true
     */
    public boolean shouldUseBlob(RollupBatch batch) {
        if (batch == null || blobDataCarrier == null) {
            return false;
        }
        GasCostEstimate calldataEst = estimateBatchGas(batch, false);
        GasCostEstimate blobEst = estimateBatchGas(batch, true);
        boolean useBlob = blobEst.getL1SettlementCostWei().compareTo(calldataEst.getL1SettlementCostWei()) < 0;
        logger.debug("Blob decision for batch: calldataCost={} blobCost={} useBlob={}",
                calldataEst.getL1SettlementCostWei(), blobEst.getL1SettlementCostWei(), useBlob);
        return useBlob;
    }

    /**
     * 估算单笔 L2 交易执行 gas。
     *
     * <p>使用 tx.gasLimit（若设置），否则使用 {@code txBaseExecutionGas} 基础值。</p>
     *
     * @param tx 交易
     * @return 执行 gas
     */
    private long estimateExecutionGas(L2Transaction tx) {
        if (tx == null) {
            return 0;
        }
        return tx.getGasLimit() > 0 ? tx.getGasLimit() : txBaseExecutionGas;
    }

    /**
     * 估算单笔 L2 交易编码为 L1 calldata 的 gas（EIP-2028）。
     *
     * <p>非零字节 16 gas，零字节 4 gas。基于 tx.rawTx 字节；
     * 若 rawTx 未设置，使用 txHash 长度估算。</p>
     *
     * @param tx 交易
     * @return calldata gas
     */
    private long estimateCalldataGas(L2Transaction tx) {
        if (tx == null) {
            return 0;
        }
        byte[] raw = tx.getRawTx();
        if (raw != null && raw.length > 0) {
            long gas = 0;
            for (byte b : raw) {
                gas += (b == 0) ? CALLDATA_ZERO_GAS : CALLDATA_NON_ZERO_GAS;
            }
            return gas;
        }
        // fallback：基于 txHash 长度估算（hex 字符串 / 2 = 字节数，假设全非零）
        String hash = tx.getTxHash();
        int byteLen = hash == null ? 32 : Math.max(32, hash.length() / 2);
        return (long) byteLen * CALLDATA_NON_ZERO_GAS;
    }

    /**
     * 估算批次总数据字节数（用于计算 blob 数）。
     *
     * @param txs 交易列表
     * @return 总字节数
     */
    private long estimateBatchDataBytes(List<L2Transaction> txs) {
        long total = 0;
        for (L2Transaction tx : txs) {
            byte[] raw = tx.getRawTx();
            if (raw != null) {
                total += raw.length;
            } else {
                String hash = tx.getTxHash();
                total += hash == null ? 32 : Math.max(32, hash.length() / 2);
            }
        }
        return total;
    }

    /**
     * 获取 L1 blob base fee（优先从 BlobDataCarrier 获取，否则用 calldata base fee / 4 估算）。
     *
     * @return L1 blob base fee
     */
    private BigInteger getL1BlobBaseFee() {
        if (blobDataCarrier != null) {
            return BigInteger.valueOf(blobDataCarrier.getBlobBaseFee());
        }
        // fallback：blob base fee 通常远低于 calldata base fee
        return l1CalldataBaseFee.divide(BigInteger.valueOf(4));
    }

    public BigInteger getL2GasPrice() {
        return l2GasPrice;
    }

    public BigInteger getL1CalldataBaseFee() {
        return l1CalldataBaseFee;
    }

    public long getL1VerificationGas() {
        return l1VerificationGas;
    }

    public long getTxBaseExecutionGas() {
        return txBaseExecutionGas;
    }

    public void setBlobDataCarrier(BlobDataCarrier blobDataCarrier) {
        this.blobDataCarrier = blobDataCarrier;
    }
}