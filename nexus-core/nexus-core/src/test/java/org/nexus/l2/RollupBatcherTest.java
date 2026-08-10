package org.nexus.l2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.gas.GasCostEstimate;
import org.nexus.l2.gas.GasCostEstimator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link RollupBatcher} 批量打包器测试。
 */
public class RollupBatcherTest {

    private RollupBatcher batcher;

    @BeforeEach
    public void setUp() {
        batcher = new RollupBatcher(10);
    }

    @Test
    public void testDefaultConstructor() {
        RollupBatcher def = new RollupBatcher();
        assertEquals(1000, def.getMaxBatchSize());
    }

    @Test
    public void testGetMaxBatchSize() {
        assertEquals(10, batcher.getMaxBatchSize());
    }

    @Test
    public void testSubmitNullTransaction() {
        batcher.submitTransaction(null);
        assertEquals(0, batcher.getMempoolSize());
    }

    @Test
    public void testSubmitTransaction() {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0xabc");
        batcher.submitTransaction(tx);
        assertEquals(1, batcher.getMempoolSize());
        assertEquals(L2TransactionStatus.PENDING, tx.getStatus());
    }

    @Test
    public void testBuildBatchEmpty() {
        RollupBatch batch = batcher.buildBatch(1L, "submitter");
        assertEquals(1L, batch.getBatchId());
        assertEquals("submitter", batch.getSubmitter());
        assertEquals(RollupBatchStatus.SUBMITTED, batch.getStatus());
        assertTrue(batch.getTransactions().isEmpty());
    }

    @Test
    public void testBuildBatchWithTransactions() {
        for (int i = 0; i < 3; i++) {
            L2Transaction tx = new L2Transaction();
            tx.setTxHash("0x" + i);
            batcher.submitTransaction(tx);
        }
        assertEquals(3, batcher.getMempoolSize());

        RollupBatch batch = batcher.buildBatch(1L, "submitter");
        assertEquals(3, batch.getTransactions().size());
        assertEquals(0, batcher.getMempoolSize());
        for (L2Transaction tx : batch.getTransactions()) {
            assertEquals(1L, tx.getBatchId());
            assertEquals(L2TransactionStatus.INCLUDED, tx.getStatus());
        }
    }

    @Test
    public void testBuildBatchMaxSize() {
        for (int i = 0; i < 15; i++) {
            L2Transaction tx = new L2Transaction();
            tx.setTxHash("0x" + i);
            batcher.submitTransaction(tx);
        }

        RollupBatch batch = batcher.buildBatch(1L, "submitter");
        assertEquals(10, batch.getTransactions().size());
        assertEquals(5, batcher.getMempoolSize());
    }

    @Test
    public void testEstimateBatchGasWithoutEstimator() {
        RollupBatch batch = new RollupBatch();
        assertNull(batcher.estimateBatchGas(batch, false));
        assertNull(batcher.estimateBatchGas(batch, true));
    }

    @Test
    public void testEstimateBatchGasWithEstimator() {
        GasCostEstimator estimator = mock(GasCostEstimator.class);
        // GasCostEstimate 是 final 类，不能 mock，使用真实实例
        GasCostEstimate estimate = new GasCostEstimate(1, 2, 3, 4, null, null, false, 1);
        RollupBatch batch = new RollupBatch();
        when(estimator.estimateBatchGas(batch, false)).thenReturn(estimate);
        injectField(batcher, "gasCostEstimator", estimator);

        assertSame(estimate, batcher.estimateBatchGas(batch, false));
        verify(estimator).estimateBatchGas(batch, false);
    }

    @Test
    public void testShouldUseBlobWithoutEstimator() {
        RollupBatch batch = new RollupBatch();
        assertFalse(batcher.shouldUseBlob(batch));
    }

    @Test
    public void testShouldUseBlobWithEstimator() {
        GasCostEstimator estimator = mock(GasCostEstimator.class);
        RollupBatch batch = new RollupBatch();
        when(estimator.shouldUseBlob(batch)).thenReturn(true);
        injectField(batcher, "gasCostEstimator", estimator);

        assertTrue(batcher.shouldUseBlob(batch));
        verify(estimator).shouldUseBlob(batch);
    }

    @Test
    public void testGetGasCostEstimator() {
        assertNull(batcher.getGasCostEstimator());
        GasCostEstimator estimator = mock(GasCostEstimator.class);
        injectField(batcher, "gasCostEstimator", estimator);
        assertSame(estimator, batcher.getGasCostEstimator());
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}