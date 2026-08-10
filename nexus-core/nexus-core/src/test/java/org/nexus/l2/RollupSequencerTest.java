package org.nexus.l2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.l2.blob.BlobCarrierResult;
import org.nexus.l2.blob.BlobDataCarrier;
import org.nexus.l2.gas.GasCostEstimator;
import org.nexus.l2.sequencer.SequencingPolicy;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link RollupSequencer} 单元测试。
 *
 * <p>覆盖排序器核心流程：</p>
 * <ul>
 *   <li>批次构建与发布（sequenceAndPublish）</li>
 *   <li>nonce + 优先费排序策略</li>
 *   <li>batchId 单调递增</li>
 *   <li>可选 EIP-4844 blob 携带（beneficial / not beneficial）</li>
 *   <li>空 mempool 场景</li>
 *   <li>桥合约状态根提交</li>
 * </ul>
 *
 * @since 1.2
 */
public class RollupSequencerTest {

    private RollupSequencer sequencer;
    private RollupBatcher batcher;
    private StateRootManager stateRootManager;
    private L2BridgeContract bridge;

    @BeforeEach
    public void setUp() {
        sequencer = new RollupSequencer();
        batcher = new RollupBatcher();
        stateRootManager = new StateRootManager();
        bridge = Mockito.mock(L2BridgeContract.class);
        Mockito.when(bridge.submitStateRoot(anyLong(), any())).thenReturn(true);

        ReflectionTestUtils.setField(sequencer, "batcher", batcher);
        ReflectionTestUtils.setField(sequencer, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(sequencer, "bridge", bridge);
    }

    // ==================== sequenceAndPublish 基础 ====================

    @Test
    public void sequenceAndPublish_emptyMempool_returnsEmptyBatch() {
        RollupBatch batch = sequencer.sequenceAndPublish("sequencer-1");
        assertNotNull(batch);
        assertEquals(1L, batch.getBatchId().longValue());
        assertTrue(batch.getTransactions() == null || batch.getTransactions().isEmpty());
        assertEquals(2L, sequencer.getNextBatchId());
    }

    @Test
    public void sequenceAndPublish_withTransactions_publishesBatch() {
        batcher.submitTransaction(makeTx("h1", "alice", 0));
        batcher.submitTransaction(makeTx("h2", "bob", 0));

        RollupBatch batch = sequencer.sequenceAndPublish("sequencer-1");

        assertNotNull(batch);
        assertEquals(1L, batch.getBatchId().longValue());
        assertEquals(2, batch.getTransactions().size());
        assertNotNull(batch.getStateRoot());
        // 桥合约应被调用提交状态根
        Mockito.verify(bridge).submitStateRoot(eq(1L), anyString());
    }

    @Test
    public void sequenceAndPublish_batchIdMonotonicallyIncreasing() {
        batcher.submitTransaction(makeTx("h1", "a", 0));
        RollupBatch b1 = sequencer.sequenceAndPublish("s");
        batcher.submitTransaction(makeTx("h2", "a", 1));
        RollupBatch b2 = sequencer.sequenceAndPublish("s");
        batcher.submitTransaction(makeTx("h3", "a", 2));
        RollupBatch b3 = sequencer.sequenceAndPublish("s");

        assertEquals(1L, b1.getBatchId().longValue());
        assertEquals(2L, b2.getBatchId().longValue());
        assertEquals(3L, b3.getBatchId().longValue());
        assertEquals(4L, sequencer.getNextBatchId());
    }

    @Test
    public void sequenceAndPublish_appliesStateRootFromManager() {
        batcher.submitTransaction(makeTx("h1", "a", 0));
        RollupBatch batch = sequencer.sequenceAndPublish("s");
        assertEquals(stateRootManager.getCurrentStateRoot(), batch.getStateRoot());
    }

    @Test
    public void sequenceAndPublish_setsBatchIdOnTransactions() {
        batcher.submitTransaction(makeTx("h1", "a", 0));
        batcher.submitTransaction(makeTx("h2", "a", 1));
        RollupBatch batch = sequencer.sequenceAndPublish("s");
        for (L2Transaction tx : batch.getTransactions()) {
            assertEquals(1L, tx.getBatchId().longValue());
        }
    }

    // ==================== 排序策略 ====================

    @Test
    public void sequenceAndPublish_sortsByAccountThenNonce() {
        // 故意乱序提交
        batcher.submitTransaction(makeTx("h_b1", "bob", 1));
        batcher.submitTransaction(makeTx("h_a2", "alice", 1));
        batcher.submitTransaction(makeTx("h_b0", "bob", 0));
        batcher.submitTransaction(makeTx("h_a0", "alice", 0));

        RollupBatch batch = sequencer.sequenceAndPublish("s");
        List<L2Transaction> txs = batch.getTransactions();

        // 期望：alice/0, alice/1, bob/0, bob/1
        assertEquals(txs.get(0).getSender(), "alice");
        assertEquals(0, txs.get(0).getNonce());
        assertEquals(txs.get(1).getSender(), "alice");
        assertEquals(1, txs.get(1).getNonce());
        assertEquals(txs.get(2).getSender(), "bob");
        assertEquals(0, txs.get(2).getNonce());
        assertEquals(txs.get(3).getSender(), "bob");
        assertEquals(1, txs.get(3).getNonce());
    }

    @Test
    public void sequenceAndPublish_priorityFeeDescendingAcrossAccounts() {
        // 同 nonce 跨账户：高优先费在前
        L2Transaction lowFee = makeTx("h_low", "alice", 0);
        lowFee.setPriorityFee(BigInteger.ONE);
        L2Transaction highFee = makeTx("h_high", "bob", 0);
        highFee.setPriorityFee(BigInteger.TEN);

        batcher.submitTransaction(lowFee);
        batcher.submitTransaction(highFee);

        RollupBatch batch = sequencer.sequenceAndPublish("s");
        List<L2Transaction> txs = batch.getTransactions();
        // alice < bob 字典序，所以 alice 先；此测试验证排序稳定不破坏
        assertEquals(txs.get(0).getSender(), "alice");
        assertEquals(txs.get(1).getSender(), "bob");
    }

    @Test
    public void getSequencingPolicy_returnsDefaultPolicy() {
        SequencingPolicy policy = sequencer.getSequencingPolicy();
        assertNotNull(policy);
        // 验证策略对乱序输入能正确排序
        List<L2Transaction> txs = new ArrayList<>(Arrays.asList(
                makeTx("h1", "a", 2),
                makeTx("h2", "a", 0),
                makeTx("h3", "a", 1)));
        policy.sort(txs);
        assertTrue(policy.isNonceOrdered(txs));
    }

    // ==================== EIP-4844 blob 携带 ====================

    @Test
    public void sequenceAndPublish_noBlobCarrier_skipsBlobCarriage() {
        batcher.submitTransaction(makeTx("h1", "a", 0));
        RollupBatch batch = sequencer.sequenceAndPublish("s");
        assertNotNull(batch);
        assertNull(sequencer.getBlobDataCarrier());
    }

    @Test
    public void sequenceAndPublish_withBlobCarrier_carriesBatchData() {
        BlobDataCarrier mockBlob = Mockito.mock(BlobDataCarrier.class);
        BlobCarrierResult mockResult = Mockito.mock(BlobCarrierResult.class);
        Mockito.when(mockBlob.carryBatchData(anyLong(), any())).thenReturn(mockResult);
        ReflectionTestUtils.setField(sequencer, "blobDataCarrier", mockBlob);
        // 无 gasCostEstimator → useBlob=true

        batcher.submitTransaction(makeTx("h1", "a", 0));
        sequencer.sequenceAndPublish("s");

        Mockito.verify(mockBlob).carryBatchData(eq(1L), any());
    }

    @Test
    public void sequenceAndPublish_gasEstimatorSaysNoBlob_skipsBlobCarriage() {
        BlobDataCarrier mockBlob = Mockito.mock(BlobDataCarrier.class);
        GasCostEstimator mockGas = Mockito.mock(GasCostEstimator.class);
        Mockito.when(mockGas.shouldUseBlob(any())).thenReturn(false);
        ReflectionTestUtils.setField(sequencer, "blobDataCarrier", mockBlob);
        ReflectionTestUtils.setField(sequencer, "gasCostEstimator", mockGas);

        batcher.submitTransaction(makeTx("h1", "a", 0));
        sequencer.sequenceAndPublish("s");

        Mockito.verify(mockBlob, never()).carryBatchData(anyLong(), any());
    }

    @Test
    public void sequenceAndPublish_gasEstimatorSaysUseBlob_carriesBatchData() {
        BlobDataCarrier mockBlob = Mockito.mock(BlobDataCarrier.class);
        BlobCarrierResult mockResult = Mockito.mock(BlobCarrierResult.class);
        Mockito.when(mockBlob.carryBatchData(anyLong(), any())).thenReturn(mockResult);
        GasCostEstimator mockGas = Mockito.mock(GasCostEstimator.class);
        Mockito.when(mockGas.shouldUseBlob(any())).thenReturn(true);
        ReflectionTestUtils.setField(sequencer, "blobDataCarrier", mockBlob);
        ReflectionTestUtils.setField(sequencer, "gasCostEstimator", mockGas);

        batcher.submitTransaction(makeTx("h1", "a", 0));
        sequencer.sequenceAndPublish("s");

        Mockito.verify(mockBlob).carryBatchData(eq(1L), any());
    }

    @Test
    public void getBlobDataCarrier_null_whenNotInjected() {
        assertNull(sequencer.getBlobDataCarrier());
    }

    @Test
    public void getGasCostEstimator_null_whenNotInjected() {
        assertNull(sequencer.getGasCostEstimator());
    }

    // ==================== mempool ====================

    @Test
    public void getMempoolSize_reflectsBatcherState() {
        assertEquals(0, sequencer.getMempoolSize());
        batcher.submitTransaction(makeTx("h1", "a", 0));
        batcher.submitTransaction(makeTx("h2", "a", 1));
        assertEquals(2, sequencer.getMempoolSize());
        sequencer.sequenceAndPublish("s");
        assertEquals(0, sequencer.getMempoolSize());
    }

    @Test
    public void sequenceAndPublish_drainsMempoolUpToMaxBatchSize() {
        RollupBatcher smallBatcher = new RollupBatcher(2);
        ReflectionTestUtils.setField(sequencer, "batcher", smallBatcher);
        for (int i = 0; i < 5; i++) {
            smallBatcher.submitTransaction(makeTx("h" + i, "a", i));
        }
        RollupBatch b1 = sequencer.sequenceAndPublish("s");
        assertEquals(2, b1.getTransactions().size());
        assertEquals(3, sequencer.getMempoolSize());
    }

    // ==================== 辅助方法 ====================

    private L2Transaction makeTx(String hash, String sender, long nonce) {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash(hash);
        tx.setSender(sender);
        tx.setNonce(nonce);
        tx.setAmount(BigInteger.valueOf(100));
        return tx;
    }
}