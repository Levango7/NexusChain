package org.nexus.l2.gas;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.L2Transaction;
import org.nexus.l2.RollupBatch;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GasCostEstimator} 单元测试。
 */
class GasCostEstimatorTest {

    private GasCostEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new GasCostEstimator();
    }

    @Test
    void defaultConstructorUsesDefaults() {
        assertEquals(GasCostEstimator.DEFAULT_L2_GAS_PRICE, estimator.getL2GasPrice());
        assertEquals(GasCostEstimator.DEFAULT_L1_CALLDATA_BASE_FEE, estimator.getL1CalldataBaseFee());
        assertEquals(GasCostEstimator.DEFAULT_L1_VERIFICATION_GAS, estimator.getL1VerificationGas());
        assertEquals(GasCostEstimator.DEFAULT_TX_BASE_EXECUTION_GAS, estimator.getTxBaseExecutionGas());
    }

    @Test
    void customConstructorNullsFallBackToDefaults() {
        GasCostEstimator e = new GasCostEstimator(null, null, 100, 200);
        assertEquals(GasCostEstimator.DEFAULT_L2_GAS_PRICE, e.getL2GasPrice());
        assertEquals(GasCostEstimator.DEFAULT_L1_CALLDATA_BASE_FEE, e.getL1CalldataBaseFee());
        assertEquals(100, e.getL1VerificationGas());
        assertEquals(200, e.getTxBaseExecutionGas());
    }

    @Test
    void estimateTxGasNullReturnsZeros() {
        GasCostEstimate e = estimator.estimateTxGas(null);
        assertEquals(0, e.getExecutionGas());
        assertEquals(0, e.getL1SettlementCostWei().intValue());
        assertEquals(0, e.getPerTxFeeWei().intValue());
    }

    @Test
    void estimateTxGasUsesGasLimitWhenSet() {
        L2Transaction tx = new L2Transaction();
        tx.setGasLimit(50000);
        tx.setTxHash("0123456789abcdef"); // 16 chars → 8 bytes
        GasCostEstimate e = estimator.estimateTxGas(tx);
        assertEquals(50000, e.getExecutionGas());
        // perTxFee = execGas * l2GasPrice (1) = 50000
        assertEquals(50000, e.getPerTxFeeWei().intValue());
    }

    @Test
    void estimateTxGasUsesBaseWhenGasLimitZero() {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0123456789abcdef");
        GasCostEstimate e = estimator.estimateTxGas(tx);
        assertEquals(GasCostEstimator.DEFAULT_TX_BASE_EXECUTION_GAS, e.getExecutionGas());
    }

    @Test
    void estimateTxGasCalldataFromRawTx() {
        L2Transaction tx = new L2Transaction();
        tx.setGasLimit(100);
        // 2 non-zero + 1 zero byte
        tx.setRawTx(new byte[]{1, 0, 2});
        GasCostEstimate e = estimator.estimateTxGas(tx);
        long expected = 2 * GasCostEstimator.CALLDATA_NON_ZERO_GAS + 1 * GasCostEstimator.CALLDATA_ZERO_GAS;
        assertEquals(expected, e.getCalldataGas());
    }

    @Test
    void estimateBatchGasNullReturnsZeros() {
        GasCostEstimate e = estimator.estimateBatchGas(null, false);
        assertEquals(0, e.getExecutionGas());
        assertEquals(0, e.getBatchSize());
        assertFalse(e.isUseBlob());
    }

    @Test
    void estimateBatchGasEmptyBatchReturnsVerificationOnly() {
        RollupBatch batch = new RollupBatch();
        batch.setTransactions(Collections.emptyList());
        GasCostEstimate e = estimator.estimateBatchGas(batch, false);
        assertEquals(0, e.getExecutionGas());
        assertEquals(GasCostEstimator.DEFAULT_L1_VERIFICATION_GAS, e.getL1VerificationGas());
        assertEquals(0, e.getBatchSize());
    }

    @Test
    void estimateBatchGasWithTransactionsCalldata() {
        L2Transaction tx1 = new L2Transaction();
        tx1.setGasLimit(100);
        tx1.setTxHash("0123456789abcdef");
        L2Transaction tx2 = new L2Transaction();
        tx2.setGasLimit(200);
        tx2.setTxHash("fedcba9876543210");

        RollupBatch batch = new RollupBatch();
        batch.setTransactions(Arrays.asList(tx1, tx2));

        GasCostEstimate e = estimator.estimateBatchGas(batch, false);
        assertEquals(2, e.getBatchSize());
        assertEquals(300, e.getExecutionGas());
        assertFalse(e.isUseBlob());
        // L1 settlement cost > 0
        assertTrue(e.getL1SettlementCostWei().signum() > 0);
        assertTrue(e.getPerTxFeeWei().signum() > 0);
    }

    @Test
    void estimateBatchGasWithBlob() {
        L2Transaction tx = new L2Transaction();
        tx.setGasLimit(100);
        tx.setRawTx(new byte[100]); // 100 zero bytes

        RollupBatch batch = new RollupBatch();
        batch.setTransactions(Collections.singletonList(tx));

        GasCostEstimate e = estimator.estimateBatchGas(batch, true);
        assertTrue(e.isUseBlob());
        assertTrue(e.getBlobGas() > 0);
        // blob base fee = calldataBaseFee / 4 = 5
        assertTrue(e.getL1SettlementCostWei().signum() > 0);
    }

    @Test
    void shouldUseBlobNullBatchReturnsFalse() {
        assertFalse(estimator.shouldUseBlob(null));
    }

    @Test
    void shouldUseBlobWithoutCarrierReturnsFalse() {
        // blobDataCarrier 未注入 → false
        RollupBatch batch = new RollupBatch();
        L2Transaction tx = new L2Transaction();
        tx.setGasLimit(100);
        tx.setTxHash("0123456789abcdef");
        batch.setTransactions(Collections.singletonList(tx));
        assertFalse(estimator.shouldUseBlob(batch));
    }

    @Test
    void customL2GasPriceAffectsPerTxFee() {
        GasCostEstimator expensive = new GasCostEstimator(
                BigInteger.valueOf(100), BigInteger.valueOf(20), 210_000L, 100_000L);
        L2Transaction tx = new L2Transaction();
        tx.setGasLimit(1000);
        tx.setTxHash("0123456789abcdef");
        GasCostEstimate e = expensive.estimateTxGas(tx);
        // perTx = execGas(1000) * l2GasPrice(100) = 100000
        assertEquals(100000, e.getPerTxFeeWei().intValue());
    }
}