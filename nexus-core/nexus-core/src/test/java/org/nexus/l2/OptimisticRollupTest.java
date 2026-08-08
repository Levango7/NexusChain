package org.nexus.l2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.l2.challenge.ChallengeConflictResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@link OptimisticRollup} 单元测试。
 *
 * <p>覆盖 Optimistic Rollup 主流程：</p>
 * <ul>
 *   <li>submitBatch → MPT 状态根 → L1 提交 → 挑战窗口开启</li>
 *   <li>verifyBatch（挑战窗口结束 → VERIFIED；窗口内 → false；已 CHALLENGED → false）</li>
 *   <li>challengeBatch（first-valid-wins / 重复 / 无效 / 窗口关闭）</li>
 *   <li>batchId 单调递增</li>
 *   <li>异常：错误证明类型、batchId 不匹配、未质押 bond</li>
 * </ul>
 *
 * @since 1.2
 */
public class OptimisticRollupTest {

    private OptimisticRollup rollup;
    private StateRootManager stateRootManager;
    private FraudProofVerifier verifier;
    private L2BridgeContract bridge;

    @BeforeEach
    public void setUp() {
        rollup = new OptimisticRollup();
        stateRootManager = new StateRootManager();
        verifier = new FraudProofVerifier(Duration.ofDays(7));
        ReflectionTestUtils.setField(verifier, "stateRootManager", stateRootManager);
        bridge = Mockito.mock(L2BridgeContract.class);
        Mockito.when(bridge.submitStateRoot(anyLong(), anyString())).thenReturn(true);

        ReflectionTestUtils.setField(rollup, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", verifier);
        ReflectionTestUtils.setField(rollup, "bridge", bridge);
    }

    // ==================== submitBatch ====================

    @Test
    public void submitBatch_returnsIncrementingBatchId() {
        long id1 = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        long id2 = rollup.submitBatch(Collections.singletonList(makeTx("h2")));
        long id3 = rollup.submitBatch(Collections.singletonList(makeTx("h3")));
        assertEquals(1L, id1);
        assertEquals(2L, id2);
        assertEquals(3L, id3);
        assertEquals(4L, rollup.getNextBatchId());
    }

    @Test
    public void submitBatch_nullTransactions_returnsBatchId() {
        long id = rollup.submitBatch(null);
        assertEquals(1L, id);
    }

    @Test
    public void submitBatch_emptyTransactions_returnsBatchId() {
        long id = rollup.submitBatch(new ArrayList<>());
        assertEquals(1L, id);
    }

    @Test
    public void submitBatch_appliesStateRootAndSubmitsToBridge() {
        L2Transaction tx = makeTx("h1");
        long id = rollup.submitBatch(Collections.singletonList(tx));
        RollupBatch batch = verifier.getBatch(id);
        assertNotNull(batch);
        assertNotNull(batch.getStateRoot());
        assertEquals(stateRootManager.getCurrentStateRoot(), batch.getStateRoot());
        Mockito.verify(bridge).submitStateRoot(eq(id), anyString());
    }

    @Test
    public void submitBatch_opensChallengeWindow() {
        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        // 窗口刚开启，不应已结束
        assertFalse(verifier.isChallengeWindowOver(id));
    }

    @Test
    public void submitBatch_setsBatchIdOnTransactions() {
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        long id = rollup.submitBatch(Arrays.asList(tx1, tx2));
        assertEquals(id, tx1.getBatchId().longValue());
        assertEquals(id, tx2.getBatchId().longValue());
    }

    @Test
    public void submitBatch_setsIncludedStatusOnTransactions() {
        L2Transaction tx = makeTx("h1");
        rollup.submitBatch(Collections.singletonList(tx));
        assertEquals(L2TransactionStatus.INCLUDED, tx.getStatus());
    }

    @Test
    public void submitBatch_withExplicitBatchIdAndSubmitter() {
        L2Transaction tx = makeTx("h1");
        long id = rollup.submitBatch(42L, Collections.singletonList(tx), "customRoot", "custom-sequencer");
        assertEquals(42L, id);
        assertEquals(verifier.getSubmitter(42L), "custom-sequencer");
        Mockito.verify(bridge).submitStateRoot(42L, "customRoot");
    }

    // ==================== verifyBatch ====================

    @Test
    public void verifyBatch_windowNotOver_returnsFalse() {
        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        assertFalse(rollup.verifyBatch(id));
    }

    @Test
    public void verifyBatch_windowOver_marksVerified() throws InterruptedException {
        // 用短窗口的 verifier
        FraudProofVerifier shortVerifier = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortVerifier, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", shortVerifier);

        L2Transaction tx = makeTx("h1");
        long id = rollup.submitBatch(Collections.singletonList(tx));
        Thread.sleep(20);

        assertTrue(rollup.verifyBatch(id));
        RollupBatch batch = shortVerifier.getBatch(id);
        assertEquals(RollupBatchStatus.VERIFIED, batch.getStatus());
        assertEquals(L2TransactionStatus.CONFIRMED, tx.getStatus());
    }

    @Test
    public void verifyBatch_unknownBatch_returnsFalse() {
        assertFalse(rollup.verifyBatch(999L));
    }

    @Test
    public void verifyBatch_challengedBatch_returnsFalse() throws InterruptedException {
        FraudProofVerifier shortVerifier = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortVerifier, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", shortVerifier);

        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        RollupBatch batch = shortVerifier.getBatch(id);
        batch.setStatus(RollupBatchStatus.CHALLENGED);
        Thread.sleep(20);

        assertFalse(rollup.verifyBatch(id));
    }

    // ==================== challengeBatch ====================

    @Test
    public void challengeBatch_invalidProofType_returnsFalse() {
        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        assertFalse(rollup.challengeBatch(id, "notAFraudProof"));
    }

    @Test
    public void challengeBatch_nullProof_returnsFalse() {
        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        assertFalse(rollup.challengeBatch(id, null));
    }

    @Test
    public void challengeBatch_batchIdMismatch_returnsFalse() {
        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        FraudProof proof = new FraudProof();
        proof.setBatchId(999L); // 不匹配
        proof.setChallenger("c1");
        assertFalse(rollup.challengeBatch(id, proof));
    }

    @Test
    public void challengeBatch_noStakedBond_returnsFalse() {
        long id = rollup.submitBatch(Collections.singletonList(makeTx("h1")));
        FraudProof proof = new FraudProof();
        proof.setBatchId(id);
        proof.setChallenger("neverStaked");
        assertFalse(rollup.challengeBatch(id, proof));
    }

    @Test
    public void challengeBatch_validProof_succeeds() {
        // 准备批次
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long id = rollup.submitBatch(Arrays.asList(tx0, tx1));

        // 挑战者质押 bond
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));

        // 构造有效欺诈证明
        FraudProof proof = buildValidFraudProof(id, 0, "alice");

        assertTrue(rollup.challengeBatch(id, proof));
        assertEquals(RollupBatchStatus.CHALLENGED, verifier.getBatch(id).getStatus());
    }

    @Test
    public void challengeBatch_invalidProof_returnsFalse() {
        L2Transaction tx = makeTx("h1");
        long id = rollup.submitBatch(Collections.singletonList(tx));
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));

        FraudProof proof = buildValidFraudProof(id, 0, "alice");
        proof.setPrevRoot("wrongRoot"); // 使 merkle 验证失败

        assertFalse(rollup.challengeBatch(id, proof));
        assertEquals(ChallengeBond.Status.SLASHED,
                verifier.getChallengeBond("alice").getStatus());
    }

    @Test
    public void challengeBatch_duplicateAfterValid_returnsTrue() {
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long id = rollup.submitBatch(Arrays.asList(tx0, tx1));
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));
        verifier.stakeChallengeBond("bob", new BigDecimal("500"));

        assertTrue(rollup.challengeBatch(id, buildValidFraudProof(id, 0, "alice")));
        assertTrue(rollup.challengeBatch(id, buildValidFraudProof(id, 0, "bob")));
        // bob bond 应被退还
        assertEquals(ChallengeBond.Status.RELEASED,
                verifier.getChallengeBond("bob").getStatus());
    }

    @Test
    public void challengeBatch_windowClosed_returnsFalse() throws InterruptedException {
        FraudProofVerifier shortVerifier = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortVerifier, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", shortVerifier);

        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long id = rollup.submitBatch(Arrays.asList(tx0, tx1));
        shortVerifier.stakeChallengeBond("alice", new BigDecimal("500"));
        Thread.sleep(20);

        assertFalse(rollup.challengeBatch(id, buildValidFraudProof(id, 0, "alice")));
    }

    // ==================== 完整生命周期 ====================

    @Test
    public void lifecycle_submitChallengeWindowOver_verify() throws InterruptedException {
        FraudProofVerifier shortVerifier = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortVerifier, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", shortVerifier);

        L2Transaction tx = makeTx("h1");
        long id = rollup.submitBatch(Collections.singletonList(tx));
        Thread.sleep(20);

        // 无挑战 → verifyBatch 成功
        assertTrue(rollup.verifyBatch(id));
        assertEquals(RollupBatchStatus.VERIFIED, shortVerifier.getBatch(id).getStatus());
        assertEquals(L2TransactionStatus.CONFIRMED, tx.getStatus());
    }

    @Test
    public void lifecycle_submitChallenged_cannotVerify() throws InterruptedException {
        FraudProofVerifier shortVerifier = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortVerifier, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", shortVerifier);

        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long id = rollup.submitBatch(Arrays.asList(tx0, tx1));
        shortVerifier.stakeChallengeBond("alice", new BigDecimal("500"));
        assertTrue(rollup.challengeBatch(id, buildValidFraudProof(id, 0, "alice")));
        Thread.sleep(20);

        // 已 CHALLENGED → verifyBatch 失败
        assertFalse(rollup.verifyBatch(id));
        assertEquals(RollupBatchStatus.CHALLENGED, shortVerifier.getBatch(id).getStatus());
    }

    // ==================== 辅助方法 ====================

    private L2Transaction makeTx(String hash) {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash(hash);
        tx.setAmount(BigInteger.valueOf(100));
        return tx;
    }

    private FraudProof buildValidFraudProof(long batchId, int txIndex, String challenger) {
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(batchId);
        assertNotNull(ctx);
        MerkleProof mp = stateRootManager.getMerkleProof(batchId, txIndex);
        assertNotNull(mp);

        FraudProof proof = new FraudProof();
        proof.setBatchId(batchId);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(txIndex);
        proof.setTx(ctx.txs.get(txIndex));
        proof.setStateBefore(ctx.recursiveRoots.get(txIndex));
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter("fraudulentClaim_" + txIndex);
        proof.setChallenger(challenger);
        return proof;
    }
}