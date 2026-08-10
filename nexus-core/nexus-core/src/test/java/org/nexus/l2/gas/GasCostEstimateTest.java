package org.nexus.l2.gas;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GasCostEstimate} 单元测试。
 */
class GasCostEstimateTest {

    @Test
    void gettersReturnConstructorValues() {
        BigInteger settlement = new BigInteger("1000");
        BigInteger perTx = new BigInteger("10");
        GasCostEstimate e = new GasCostEstimate(100, 200, 300, 400,
                settlement, perTx, true, 4);
        assertEquals(100, e.getExecutionGas());
        assertEquals(200, e.getCalldataGas());
        assertEquals(300, e.getBlobGas());
        assertEquals(400, e.getL1VerificationGas());
        assertEquals(settlement, e.getL1SettlementCostWei());
        assertEquals(perTx, e.getPerTxFeeWei());
        assertTrue(e.isUseBlob());
        assertEquals(4, e.getBatchSize());
    }

    @Test
    void nullSettlementBecomesZero() {
        GasCostEstimate e = new GasCostEstimate(0, 0, 0, 0, null, null, false, 0);
        assertEquals(BigInteger.ZERO, e.getL1SettlementCostWei());
        assertEquals(BigInteger.ZERO, e.getPerTxFeeWei());
    }

    @Test
    void l1SettlementGasUsesBlobWhenEnabled() {
        GasCostEstimate e = new GasCostEstimate(0, 200, 300, 400,
                BigInteger.ZERO, BigInteger.ZERO, true, 1);
        // useBlob=true → blobGas + verification
        assertEquals(300 + 400, e.getL1SettlementGas());
    }

    @Test
    void l1SettlementGasUsesCalldataWhenDisabled() {
        GasCostEstimate e = new GasCostEstimate(0, 200, 300, 400,
                BigInteger.ZERO, BigInteger.ZERO, false, 1);
        // useBlob=false → calldataGas + verification
        assertEquals(200 + 400, e.getL1SettlementGas());
    }

    @Test
    void totalGasIsExecutionPlusL1Settlement() {
        GasCostEstimate e = new GasCostEstimate(1000, 200, 300, 400,
                BigInteger.ZERO, BigInteger.ZERO, false, 1);
        assertEquals(1000 + 200 + 400, e.getTotalGas());
    }

    @Test
    void toStringContainsKeyFields() {
        GasCostEstimate e = new GasCostEstimate(1, 2, 3, 4,
                BigInteger.TEN, BigInteger.ONE, true, 2);
        String s = e.toString();
        assertNotNull(s);
        assertTrue(s.contains("GasCostEstimate"));
        assertTrue(s.contains("useBlob=true"));
        assertTrue(s.contains("batchSize=2"));
    }
}