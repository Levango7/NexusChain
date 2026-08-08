package org.nexus.l2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StateRootManager} 单元测试。
 *
 * <p>覆盖状态根计算、批次上下文、递归根链、Merkle 证明生成、
 * verifyTransition 等核心逻辑。重点验证 applyTx 纯函数的确定性
 * 与递归根链一致性（单步欺诈证明的正确性前提）。</p>
 *
 * @since 1.2
 */
public class StateRootManagerTest {

    private StateRootManager manager;

    @BeforeEach
    public void setUp() {
        manager = new StateRootManager();
    }

    // ==================== 初始状态 ====================

    @Test
    public void initialState_hasNonEmptyRoots() {
        assertNotNull(manager.getCurrentStateRoot());
        assertNotNull(manager.getCurrentRecursiveRoot());
        assertNotEquals(manager.getCurrentStateRoot(), "");
        assertNotEquals(manager.getCurrentRecursiveRoot(), "");
    }

    @Test
    public void initialState_emptyTrieRoot() {
        assertEquals(MerklePatriciaTrie.EMPTY_ROOT, manager.getCurrentStateRoot());
    }

    // ==================== applyBatch ====================

    @Test
    public void applyBatch_nullBatch_returnsCurrentRoot() {
        String before = manager.getCurrentStateRoot();
        String after = manager.applyBatch(null);
        assertEquals(before, after);
    }

    @Test
    public void applyBatch_emptyTxList_updatesContextButRootUnchangedForMpt() {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(1L);
        batch.setTransactions(new ArrayList<>());
        String before = manager.getCurrentStateRoot();
        String after = manager.applyBatch(batch);
        // 空 tx 不修改 MPT
        assertEquals(before, after);
        StateRootManager.BatchContext ctx = manager.getBatchContext(1L);
        assertNotNull(ctx);
        assertEquals(0, ctx.txs.size());
    }

    @Test
    public void applyBatch_withTransactions_updatesStateRoot() {
        RollupBatch batch = buildBatch(1L, Arrays.asList(makeTx("h1"), makeTx("h2")));
        String before = manager.getCurrentStateRoot();
        String after = manager.applyBatch(batch);
        assertNotEquals(before, after);
        assertNotNull(after);
    }

    @Test
    public void applyBatch_recordsBatchContext() {
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        RollupBatch batch = buildBatch(7L, Arrays.asList(tx1, tx2));
        manager.applyBatch(batch);

        StateRootManager.BatchContext ctx = manager.getBatchContext(7L);
        assertNotNull(ctx);
        assertEquals(2, ctx.txs.size());
        assertEquals(tx1, ctx.txs.get(0));
        assertEquals(tx2, ctx.txs.get(1));
        assertNotNull(ctx.batchTxRoot);
        assertNotNull(ctx.recursiveRoots);
        // n 笔 tx → n+1 个递归根
        assertEquals(3, ctx.recursiveRoots.size());
        assertNotNull(ctx.prevRoot);
        assertNotNull(ctx.postRoot);
    }

    @Test
    public void applyBatch_recursiveRootsChainIsConsistent() {
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        L2Transaction tx3 = makeTx("h3");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx1, tx2, tx3));
        manager.applyBatch(batch);

        StateRootManager.BatchContext ctx = manager.getBatchContext(1L);
        // 递归根链：每个 root[i+1] = applyTx(root[i], tx[i])
        for (int i = 0; i < ctx.txs.size(); i++) {
            String expected = StateRootManager.applyTx(ctx.recursiveRoots.get(i), ctx.txs.get(i));
            assertEquals(expected, ctx.recursiveRoots.get(i + 1), "recursive root chain break at step " + i);
        }
    }

    @Test
    public void applyBatch_multipleBatches_stateRootAccumulates() {
        RollupBatch b1 = buildBatch(1L, Collections.singletonList(makeTx("h1")));
        RollupBatch b2 = buildBatch(2L, Collections.singletonList(makeTx("h2")));
        String r0 = manager.getCurrentStateRoot();
        String r1 = manager.applyBatch(b1);
        String r2 = manager.applyBatch(b2);
        assertNotEquals(r0, r1);
        assertNotEquals(r1, r2);
        assertEquals(r2, manager.getCurrentStateRoot());
    }

    // ==================== applyTx 纯函数 ====================

    @Test
    public void applyTx_isDeterministic() {
        L2Transaction tx = makeTx("hash1");
        String r1 = StateRootManager.applyTx("prevRoot", tx);
        String r2 = StateRootManager.applyTx("prevRoot", tx);
        assertEquals(r1, r2);
    }

    @Test
    public void applyTx_differentInputs_produceDifferentOutputs() {
        L2Transaction tx1 = makeTx("hash1");
        L2Transaction tx2 = makeTx("hash2");
        String r1 = StateRootManager.applyTx("prevRoot", tx1);
        String r2 = StateRootManager.applyTx("prevRoot", tx2);
        assertNotEquals(r1, r2);

        String r3 = StateRootManager.applyTx("differentPrev", tx1);
        assertNotEquals(r1, r3);
    }

    @Test
    public void applyTx_nullPrevRoot_doesNotThrow() {
        L2Transaction tx = makeTx("h");
        String result = StateRootManager.applyTx(null, tx);
        assertNotNull(result);
    }

    @Test
    public void applyTx_nullTx_doesNotThrow() {
        String result = StateRootManager.applyTx("prev", null);
        assertNotNull(result);
    }

    @Test
    public void applyTx_usesRawTxBytes() {
        L2Transaction tx1 = makeTx("sameHash");
        L2Transaction tx2 = makeTx("sameHash");
        tx2.setRawTx(new byte[]{1, 2, 3}); // 不同 rawTx
        String r1 = StateRootManager.applyTx("prev", tx1);
        String r2 = StateRootManager.applyTx("prev", tx2);
        assertNotEquals(r1, r2);
    }

    // ==================== verifyTransition ====================

    @Test
    public void verifyTransition_validChain_returnsTrue() {
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx1, tx2));

        String prevRoot = "genesis";
        String r1 = StateRootManager.applyTx(prevRoot, tx1);
        String r2 = StateRootManager.applyTx(r1, tx2);

        assertTrue(manager.verifyTransition(prevRoot, r2, batch));
    }

    @Test
    public void verifyTransition_wrongNewRoot_returnsFalse() {
        L2Transaction tx = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        assertFalse(manager.verifyTransition("genesis", "wrongRoot", batch));
    }

    @Test
    public void verifyTransition_nullArgs_returnsFalse() {
        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h")));
        assertFalse(manager.verifyTransition(null, "new", batch));
        assertFalse(manager.verifyTransition("prev", null, batch));
        assertFalse(manager.verifyTransition("prev", "new", null));
    }

    // ==================== Merkle 证明生成 ====================

    @Test
    public void getMerkleProof_returnsValidProof() {
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx0, tx1, tx2));
        manager.applyBatch(batch);

        StateRootManager.BatchContext ctx = manager.getBatchContext(1L);
        for (int i = 0; i < 3; i++) {
            MerkleProof proof = manager.getMerkleProof(1L, i);
            assertNotNull(proof, "proof for tx " + i);
            assertTrue(MerklePatriciaTrie.verifyProof(proof, ctx.batchTxRoot), "verify proof for tx " + i);
        }
    }

    @Test
    public void getMerkleProof_unknownBatch_returnsNull() {
        assertNull(manager.getMerkleProof(999L, 0));
    }

    @Test
    public void getMerkleProof_noTransactions_returnsNull() {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(1L);
        batch.setTransactions(new ArrayList<>());
        manager.applyBatch(batch);
        assertNull(manager.getMerkleProof(1L, 0));
    }

    // ==================== stateBefore / stateAfter ====================

    @Test
    public void getStateBefore_andStateAfter_matchRecursiveRoots() {
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx1, tx2));
        manager.applyBatch(batch);

        StateRootManager.BatchContext ctx = manager.getBatchContext(1L);
        assertEquals(ctx.recursiveRoots.get(0), manager.getStateBefore(1L, 0));
        assertEquals(ctx.recursiveRoots.get(1), manager.getStateAfter(1L, 0));
        assertEquals(ctx.recursiveRoots.get(1), manager.getStateBefore(1L, 1));
        assertEquals(ctx.recursiveRoots.get(2), manager.getStateAfter(1L, 1));
    }

    @Test
    public void getStateBefore_outOfRange_returnsNull() {
        L2Transaction tx = makeTx("h");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        manager.applyBatch(batch);
        assertNull(manager.getStateBefore(1L, -1));
        assertNull(manager.getStateBefore(1L, 5));
        assertNull(manager.getStateBefore(999L, 0));
    }

    @Test
    public void getStateAfter_outOfRange_returnsNull() {
        L2Transaction tx = makeTx("h");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        manager.applyBatch(batch);
        assertNull(manager.getStateAfter(1L, -1));
        assertNull(manager.getStateAfter(1L, 5));
        assertNull(manager.getStateAfter(999L, 0));
    }

    // ==================== commitToL1 / 历史 ====================

    @Test
    public void commitToL1_recordssHistory() {
        String root1 = "root1";
        String root2 = "root2";
        assertTrue(manager.commitToL1(1L, root1));
        assertTrue(manager.commitToL1(2L, root2));
        assertEquals(2, manager.getCommittedBatchCount());
        assertEquals(root1, manager.getCommittedStateRoot(0));
        assertEquals(root2, manager.getCommittedStateRoot(1));
    }

    @Test
    public void getCommittedStateRoot_outOfRange_returnsNull() {
        manager.commitToL1(1L, "root");
        assertNull(manager.getCommittedStateRoot(-1));
        assertNull(manager.getCommittedStateRoot(99));
    }

    @Test
    public void getBatchTxRoot_unknownBatch_returnsNull() {
        assertNull(manager.getBatchTxRoot(999L));
    }

    @Test
    public void getStateTrie_isLiveReference() {
        MerklePatriciaTrie trie = manager.getStateTrie();
        assertNotNull(trie);
        // applyBatch 后 trie root 应等于 currentStateRoot
        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h")));
        manager.applyBatch(batch);
        assertEquals(trie.getRoot(), manager.getCurrentStateRoot());
    }

    // ==================== 辅助方法 ====================

    private L2Transaction makeTx(String hash) {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash(hash);
        tx.setAmount(BigInteger.valueOf(100));
        return tx;
    }

    private RollupBatch buildBatch(long id, List<L2Transaction> txs) {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(id);
        batch.setTransactions(new ArrayList<>(txs));
        batch.setStatus(RollupBatchStatus.SUBMITTED);
        return batch;
    }
}