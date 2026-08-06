package org.nexus.l2;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.nexus.l2.challenge.ChallengeConflictResolver;
import org.nexus.l2.challenge.ChallengeConflictResult;
import org.nexus.l2.challenge.ChallengePeriodPolicy;
import org.nexus.consensus.pos.SlashingService;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * {@link FraudProofVerifier} 单元测试 —— L2 欺诈证明验证器。
 *
 * <p>覆盖范围（审计报告 §2.2/§3.3 强调的恶意提交者场景）：</p>
 * <ul>
 *   <li>Merkle 证明验证（单步欺诈证明）</li>
 *   <li>单步状态转换重算</li>
 *   <li>二分定位出错步（generateFraudProof）</li>
 *   <li>bond 质押 / 罚没 / 释放</li>
 *   <li>first-valid-wins 多挑战者冲突解决</li>
 *   <li>动态挑战期策略（高价值延长 + 可疑行为延长）</li>
 *   <li><b>恶意提交者场景</b>：无效证明、重复挑战、过期挑战、未质押 bond、批次不存在</li>
 *   <li>finalizeBatch 流程</li>
 *   <li>slashSubmitter / rewardChallenger / markChallenged</li>
 * </ul>
 *
 * @since 1.2
 */
public class FraudProofVerifierTest {

    private StateRootManager stateRootManager;
    private FraudProofVerifier verifier;

    @Before
    public void setUp() {
        stateRootManager = new StateRootManager();
        verifier = new FraudProofVerifier();
        ReflectionTestUtils.setField(verifier, "stateRootManager", stateRootManager);
    }

    // ==================== onSubmit / 挑战窗口 ====================

    @Test
    public void onSubmit_opensChallengeWindow() {
        verifier.onSubmit(1L);
        // 窗口刚开启，不应已结束
        assertFalse(verifier.isChallengeWindowOver(1L));
    }

    @Test
    public void isChallengeWindowOver_unknownBatch_returnsFalse() {
        assertFalse(verifier.isChallengeWindowOver(999L));
    }

    @Test
    public void onSubmit_withBatch_recordsBatchAndSubmitter() {
        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h1")));
        verifier.onSubmit(batch, "submitter-A");
        assertEquals(batch, verifier.getBatch(1L));
        assertEquals("submitter-A", verifier.getSubmitter(1L));
        assertFalse(verifier.isChallengeWindowOver(1L));
    }

    @Test
    public void onSubmit_nullBatch_isNoOp() {
        verifier.onSubmit(null, "s");
        // 不应抛异常，且无副作用
    }

    @Test
    public void challengeWindowOver_afterDurationExpires() throws InterruptedException {
        FraudProofVerifier shortWindow = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortWindow, "stateRootManager", stateRootManager);
        shortWindow.onSubmit(1L);
        Thread.sleep(20);
        assertTrue(shortWindow.isChallengeWindowOver(1L));
    }

    // ==================== verifyFraudProof (整批，旧接口) ====================

    @Test
    public void verifyFraudProof_legacy_nullBatch_returnsFalse() {
        assertFalse(verifier.verifyFraudProof(1L, "prev", "new", null));
    }

    @Test
    public void verifyFraudProof_legacy_transitionMismatch_returnsTrue() {
        L2Transaction tx = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        // 用错误的 newRoot → transition 不匹配 → 欺诈成立
        boolean result = verifier.verifyFraudProof(1L, "prevRoot", "wrongNewRoot", batch);
        assertTrue(result);
    }

    @Test
    public void verifyFraudProof_legacy_validTransition_returnsFalse() {
        L2Transaction tx = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        String prevRoot = "genesis";
        String newRoot = StateRootManager.applyTx(prevRoot, tx);
        // 正确的状态转换 → 不是欺诈
        boolean result = verifier.verifyFraudProof(1L, prevRoot, newRoot, batch);
        assertFalse(result);
    }

    // ==================== verifyFraudProof (单步二分) ====================

    @Test
    public void verifyFraudProof_singleStep_nullProof_returnsFalse() {
        assertFalse(verifier.verifyFraudProof((FraudProof) null));
    }

    @Test
    public void verifyFraudProof_singleStep_nullMerkleProof_returnsFalse() {
        FraudProof proof = new FraudProof();
        proof.setBatchId(1L);
        proof.setPrevRoot("root");
        assertFalse(verifier.verifyFraudProof(proof));
    }

    @Test
    public void verifyFraudProof_singleStep_invalidMerkleProof_returnsFalse() {
        // 准备真实批次上下文以获得有效 merkle proof，然后用错误的 prevRoot 使 merkle 验证失败
        RollupBatch batch = buildBatch(1L, Arrays.asList(makeTx("h1"), makeTx("h2")));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);
        MerkleProof mp = stateRootManager.getMerkleProof(1L, 0);

        FraudProof proof = new FraudProof();
        proof.setBatchId(1L);
        proof.setPrevRoot("wrongRoot"); // 不匹配 → merkle 验证失败
        proof.setTxIndex(0);
        proof.setTx(ctx.txs.get(0));
        proof.setStateBefore(ctx.recursiveRoots.get(0));
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter("whatever");

        assertFalse(verifier.verifyFraudProof(proof));
    }

    @Test
    public void verifyFraudProof_singleStep_fraudDetected_returnsTrue() {
        // 构造真实批次，挑战者重算发现 step k 处声明状态错误
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx0, tx1));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);

        // 挑战 step 0：声称的 stateAfter 与重算不一致
        MerkleProof mp = stateRootManager.getMerkleProof(1L, 0);
        String stateBefore = ctx.recursiveRoots.get(0);
        String recomputed = StateRootManager.applyTx(stateBefore, tx0);

        FraudProof proof = new FraudProof();
        proof.setBatchId(1L);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(0);
        proof.setTx(tx0);
        proof.setStateBefore(stateBefore);
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter("fraudulentClaim"); // ≠ recomputed

        assertTrue(verifier.verifyFraudProof(proof));
    }

    @Test
    public void verifyFraudProof_singleStep_claimedStateAfterNull_returnsTrue() {
        L2Transaction tx = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);
        MerkleProof mp = stateRootManager.getMerkleProof(1L, 0);

        FraudProof proof = new FraudProof();
        proof.setBatchId(1L);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(0);
        proof.setTx(tx);
        proof.setStateBefore(ctx.recursiveRoots.get(0));
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter(null); // null → 视为欺诈

        assertTrue(verifier.verifyFraudProof(proof));
    }

    @Test
    public void verifyFraudProof_singleStep_batchIsHonest_returnsFalse() {
        // 提交者诚实：claimedStateAfter == 重算结果 → 不是欺诈
        L2Transaction tx = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);
        MerkleProof mp = stateRootManager.getMerkleProof(1L, 0);
        String stateBefore = ctx.recursiveRoots.get(0);
        String recomputed = StateRootManager.applyTx(stateBefore, tx);

        FraudProof proof = new FraudProof();
        proof.setBatchId(1L);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(0);
        proof.setTx(tx);
        proof.setStateBefore(stateBefore);
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter(recomputed); // 一致 → 非欺诈

        assertFalse(verifier.verifyFraudProof(proof));
    }

    // ==================== generateFraudProof (二分定位) ====================

    @Test
    public void generateFraudProof_batchIsValid_returnsNull() {
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx0, tx1));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);

        // 挑战者重算与提交者声明一致 → 无欺诈
        FraudProof proof = verifier.generateFraudProof(1L, "challenger", new ArrayList<>(ctx.recursiveRoots));
        assertNull(proof);
    }

    @Test
    public void generateFraudProof_locatesMismatchStep() {
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx0, tx1, tx2));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);

        // 构造错误的 claimedRoots：在 step 1 处偏离
        List<String> claimedRoots = new ArrayList<>(ctx.recursiveRoots);
        claimedRoots.set(2, "fraudulentRootAtStep1");

        FraudProof proof = verifier.generateFraudProof(1L, "challenger-A", claimedRoots);
        assertNotNull(proof);
        assertEquals(1L, proof.getBatchId());
        assertEquals(1, proof.getTxIndex()); // 二分定位到 step 1
        assertEquals("challenger-A", proof.getChallenger());
        assertNotNull(proof.getMerkleProof());
        assertNotNull(proof.getTx());
        // 生成的证明应通过验证
        assertTrue(verifier.verifyFraudProof(proof));
    }

    @Test
    public void generateFraudProof_noBatchContext_returnsNull() {
        assertNull(verifier.generateFraudProof(999L, "c", Collections.singletonList("r")));
    }

    @Test
    public void generateFraudProof_emptyTxs_returnsNull() {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(1L);
        batch.setTransactions(new ArrayList<>());
        stateRootManager.applyBatch(batch);
        assertNull(verifier.generateFraudProof(1L, "c", Collections.singletonList("r")));
    }

    @Test
    public void generateFraudProof_claimedRootsSizeMismatch_returnsNull() {
        L2Transaction tx = makeTx("h");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        stateRootManager.applyBatch(batch);
        // n=1 → 期望 claimedRoots 长度 2，传 1 → 失败
        assertNull(verifier.generateFraudProof(1L, "c", Collections.singletonList("only")));
        // null claimedRoots
        assertNull(verifier.generateFraudProof(1L, "c", null));
    }

    @Test
    public void generateFraudProof_includesChallengeBondAmount() {
        L2Transaction tx = makeTx("h");
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        stateRootManager.applyBatch(batch);
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(1L);

        verifier.stakeChallengeBond("challenger-X", new BigDecimal("500"));
        List<String> claimedRoots = new ArrayList<>(ctx.recursiveRoots);
        claimedRoots.set(1, "wrong");

        FraudProof proof = verifier.generateFraudProof(1L, "challenger-X", claimedRoots);
        assertNotNull(proof);
        assertEquals(new BigDecimal("500"), proof.getChallengeBond());
    }

    // ==================== bond 质押 / 释放 ====================

    @Test
    public void stakeChallengeBond_validArgs_succeeds() {
        assertTrue(verifier.stakeChallengeBond("c1", new BigDecimal("100")));
        ChallengeBond bond = verifier.getChallengeBond("c1");
        assertNotNull(bond);
        assertEquals(new BigDecimal("100"), bond.getAmount());
        assertEquals(ChallengeBond.Status.STAKED, bond.getStatus());
    }

    @Test
    public void stakeChallengeBond_nullChallerId_returnsFalse() {
        assertFalse(verifier.stakeChallengeBond(null, new BigDecimal("100")));
    }

    @Test
    public void stakeChallengeBond_nullAmount_returnsFalse() {
        assertFalse(verifier.stakeChallengeBond("c1", null));
    }

    @Test
    public void stakeChallengeBond_nonPositiveAmount_returnsFalse() {
        assertFalse(verifier.stakeChallengeBond("c1", BigDecimal.ZERO));
        assertFalse(verifier.stakeChallengeBond("c1", new BigDecimal("-1")));
    }

    @Test
    public void stakeChallengeBond_alreadyStaked_returnsFalse() {
        assertTrue(verifier.stakeChallengeBond("c1", new BigDecimal("100")));
        assertFalse(verifier.stakeChallengeBond("c1", new BigDecimal("200")));
    }

    @Test
    public void stakeChallengeBond_afterReleased_canStakeAgain() {
        assertTrue(verifier.stakeChallengeBond("c1", new BigDecimal("100")));
        assertTrue(verifier.releaseChallengeBond("c1"));
        assertTrue(verifier.stakeChallengeBond("c1", new BigDecimal("200")));
    }

    @Test
    public void releaseChallengeBond_unknownChallenger_returnsFalse() {
        assertFalse(verifier.releaseChallengeBond("nope"));
    }

    @Test
    public void releaseChallengeBond_existingChallenger_succeeds() {
        verifier.stakeChallengeBond("c1", new BigDecimal("100"));
        assertTrue(verifier.releaseChallengeBond("c1"));
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("c1").getStatus());
    }

    // ==================== submitChallenge: 基础场景 ====================

    @Test
    public void submitChallenge_nullProof_returnsInvalidProof() {
        assertEquals(ChallengeConflictResult.INVALID_PROOF, verifier.submitChallenge(null));
    }

    @Test
    public void submitChallenge_batchNotFound_returnsBatchNotFound() {
        // 先建立 batch 1 的上下文以构造 proof，再将 batchId 改为不存在的 999
        stateRootManager.applyBatch(buildBatch(1L, Arrays.asList(makeTx("h0"), makeTx("h1"))));
        FraudProof proof = buildValidProofSetup(1L, 0);
        proof.setBatchId(999L); // 不存在的批次
        verifier.stakeChallengeBond("c1", new BigDecimal("100"));
        proof.setChallenger("c1");
        assertEquals(ChallengeConflictResult.BATCH_NOT_FOUND, verifier.submitChallenge(proof));
    }

    @Test
    public void submitChallenge_noBond_returnsNoBond() {
        RollupBatch batch = buildBatch(1L, Arrays.asList(makeTx("h0"), makeTx("h1")));
        stateRootManager.applyBatch(batch);
        verifier.onSubmit(batch, "submitter");
        FraudProof proof = buildValidProofSetup(1L, 0);
        proof.setChallenger("neverStaked");
        assertEquals(ChallengeConflictResult.NO_BOND, verifier.submitChallenge(proof));
    }

    @Test
    public void submitChallenge_releasedBond_returnsNoBond() {
        RollupBatch batch = buildBatch(1L, Arrays.asList(makeTx("h0"), makeTx("h1")));
        stateRootManager.applyBatch(batch);
        verifier.onSubmit(batch, "submitter");
        verifier.stakeChallengeBond("c1", new BigDecimal("100"));
        verifier.releaseChallengeBond("c1"); // 已释放 → 非 STAKED
        FraudProof proof = buildValidProofSetup(1L, 0);
        proof.setChallenger("c1");
        assertEquals(ChallengeConflictResult.NO_BOND, verifier.submitChallenge(proof));
    }

    // ==================== submitChallenge: first-valid-wins 多挑战者 ====================

    @Test
    public void submitChallenge_firstValidProof_firstChallengerWins() {
        setupBatchAndSubmitter(1L, "malicious-sequencer");
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));

        FraudProof proof = buildValidFraudProof(1L, 0, "alice");
        ChallengeConflictResult result = verifier.submitChallenge(proof);

        assertEquals(ChallengeConflictResult.FIRST_VALID, result);
        // 批次应被标记为 CHALLENGED
        assertEquals(RollupBatchStatus.CHALLENGED, verifier.getBatch(1L).getStatus());
        // alice 的 bond 应被释放（挑战成功）
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("alice").getStatus());
    }

    @Test
    public void submitChallenge_secondValidChallenger_bondRefunded() {
        // 注入真实 ChallengeConflictResolver 以启用 first-valid-wins 多挑战者冲突解决
        ChallengeConflictResolver resolver = new ChallengeConflictResolver();
        ReflectionTestUtils.setField(verifier, "conflictResolver", resolver);

        setupBatchAndSubmitter(1L, "malicious-sequencer");
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));
        verifier.stakeChallengeBond("bob", new BigDecimal("500"));

        ChallengeConflictResult r1 = verifier.submitChallenge(buildValidFraudProof(1L, 0, "alice"));
        ChallengeConflictResult r2 = verifier.submitChallenge(buildValidFraudProof(1L, 0, "bob"));

        assertEquals(ChallengeConflictResult.FIRST_VALID, r1);
        assertEquals(ChallengeConflictResult.DUPLICATE_AFTER_VALID, r2);
        // bob 的 bond 应被退还（RELEASED），而非罚没
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("bob").getStatus());
        // alice 仍是首个有效挑战者
        assertEquals("alice", resolver.getFirstValidChallenger(1L));
    }

    // ==================== 恶意提交者场景（审计重点） ====================

    @Test
    public void maliciousChallenger_invalidProof_bondSlashed() {
        // 场景：恶意挑战者提交无效欺诈证明（merkle 不匹配）→ bond 罚没
        setupBatchAndSubmitter(1L, "honest-sequencer");
        verifier.stakeChallengeBond("malicious-attacker", new BigDecimal("1000"));

        FraudProof proof = buildValidProofSetup(1L, 0);
        proof.setChallenger("malicious-attacker");
        proof.setPrevRoot("wrongRoot"); // 使 merkle 验证失败 → proof invalid

        ChallengeConflictResult result = verifier.submitChallenge(proof);
        assertEquals(ChallengeConflictResult.INVALID_PROOF, result);
        assertEquals(ChallengeBond.Status.SLASHED,
                verifier.getChallengeBond("malicious-attacker").getStatus());
    }

    @Test
    public void maliciousChallenger_invalidProofAfterValid_bondStillSlashed() {
        // 场景：已有有效证明后，恶意挑战者提交无效证明 → 仍罚没
        setupBatchAndSubmitter(1L, "fraud-sequencer");
        verifier.stakeChallengeBond("honest", new BigDecimal("500"));
        verifier.stakeChallengeBond("malicious", new BigDecimal("500"));

        verifier.submitChallenge(buildValidFraudProof(1L, 0, "honest")); // FIRST_VALID

        // malicious 提交无效证明
        FraudProof badProof = buildValidProofSetup(1L, 0);
        badProof.setChallenger("malicious");
        badProof.setPrevRoot("wrongRoot");
        ChallengeConflictResult result = verifier.submitChallenge(badProof);

        assertEquals(ChallengeConflictResult.INVALID_PROOF, result);
        assertEquals(ChallengeBond.Status.SLASHED,
                verifier.getChallengeBond("malicious").getStatus());
    }

    @Test
    public void expiredChallenge_windowClosed_returnsWindowClosed() throws InterruptedException {
        // 场景：挑战窗口已过期，任何挑战被拒绝
        FraudProofVerifier shortWindow = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortWindow, "stateRootManager", stateRootManager);

        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h")));
        stateRootManager.applyBatch(batch);
        shortWindow.onSubmit(batch, "submitter");
        shortWindow.stakeChallengeBond("c1", new BigDecimal("100"));
        Thread.sleep(20); // 窗口过期

        FraudProof proof = buildValidFraudProof(1L, 0, "c1");
        ChallengeConflictResult result = shortWindow.submitChallenge(proof);
        assertEquals(ChallengeConflictResult.WINDOW_CLOSED, result);
    }

    @Test
    public void duplicateChallenge_sameChallenger_secondAttemptRejected() {
        // 同一挑战者重复挑战（无 conflictResolver 旧逻辑下，第二次仍走验证）
        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));

        ChallengeConflictResult r1 = verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));
        assertEquals(ChallengeConflictResult.FIRST_VALID, r1);
        // 第一次后 bond 已 RELEASED → 第二次 NO_BOND
        ChallengeConflictResult r2 = verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));
        assertEquals(ChallengeConflictResult.NO_BOND, r2);
    }

    // ==================== submitChallenge: 旧逻辑（无 conflictResolver） ====================

    @Test
    public void legacyChallenge_validProof_firstValid() {
        // verifier 默认未注入 conflictResolver → 走 legacyChallenge
        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));
        ChallengeConflictResult result = verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));
        assertEquals(ChallengeConflictResult.FIRST_VALID, result);
        assertEquals(RollupBatchStatus.CHALLENGED, verifier.getBatch(1L).getStatus());
    }

    @Test
    public void legacyChallenge_invalidProof_bondSlashed() {
        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));
        FraudProof proof = buildValidFraudProof(1L, 0, "c1");
        proof.setPrevRoot("wrongRoot"); // 使 merkle 验证失败
        ChallengeConflictResult result = verifier.submitChallenge(proof);
        assertEquals(ChallengeConflictResult.INVALID_PROOF, result);
        assertEquals(ChallengeBond.Status.SLASHED, verifier.getChallengeBond("c1").getStatus());
    }

    // ==================== submitChallenge: 注入 conflictResolver mock ====================

    @Test
    public void submitChallenge_withConflictResolver_delegatesToResolver() {
        ChallengeConflictResolver mockResolver = Mockito.mock(ChallengeConflictResolver.class);
        Mockito.when(mockResolver.resolveChallenge(anyLong(), anyString(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(ChallengeConflictResult.FIRST_VALID);
        ReflectionTestUtils.setField(verifier, "conflictResolver", mockResolver);

        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));
        verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));

        Mockito.verify(mockResolver).resolveChallenge(eq(1L), eq("c1"), Mockito.anyBoolean(), Mockito.anyBoolean());
        Mockito.verify(mockResolver).recordFirstValidProof(eq(1L), any());
    }

    @Test
    public void submitChallenge_resolverReturnsDuplicate_bondReleased() {
        ChallengeConflictResolver mockResolver = Mockito.mock(ChallengeConflictResolver.class);
        Mockito.when(mockResolver.resolveChallenge(anyLong(), anyString(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(ChallengeConflictResult.DUPLICATE_AFTER_VALID);
        ReflectionTestUtils.setField(verifier, "conflictResolver", mockResolver);

        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));
        ChallengeConflictResult result = verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));
        assertEquals(ChallengeConflictResult.DUPLICATE_AFTER_VALID, result);
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("c1").getStatus());
    }

    @Test
    public void submitChallenge_resolverReturnsInvalid_bondSlashed() {
        ChallengeConflictResolver mockResolver = Mockito.mock(ChallengeConflictResolver.class);
        Mockito.when(mockResolver.resolveChallenge(anyLong(), anyString(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(ChallengeConflictResult.INVALID_PROOF);
        ReflectionTestUtils.setField(verifier, "conflictResolver", mockResolver);

        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));
        ChallengeConflictResult result = verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));
        assertEquals(ChallengeConflictResult.INVALID_PROOF, result);
        assertEquals(ChallengeBond.Status.SLASHED, verifier.getChallengeBond("c1").getStatus());
    }

    @Test
    public void submitChallenge_resolverReturnsWindowClosed_bondUnchanged() {
        ChallengeConflictResolver mockResolver = Mockito.mock(ChallengeConflictResolver.class);
        Mockito.when(mockResolver.resolveChallenge(anyLong(), anyString(), Mockito.anyBoolean(), Mockito.anyBoolean()))
                .thenReturn(ChallengeConflictResult.WINDOW_CLOSED);
        ReflectionTestUtils.setField(verifier, "conflictResolver", mockResolver);

        setupBatchAndSubmitter(1L, "sequencer");
        verifier.stakeChallengeBond("c1", new BigDecimal("500"));
        ChallengeConflictResult result = verifier.submitChallenge(buildValidFraudProof(1L, 0, "c1"));
        assertEquals(ChallengeConflictResult.WINDOW_CLOSED, result);
        // bond 状态不变
        assertEquals(ChallengeBond.Status.STAKED, verifier.getChallengeBond("c1").getStatus());
    }

    // ==================== slashSubmitter ====================

    @Test
    public void slashSubmitter_nullSubmitter_returnsZero() {
        assertEquals(BigDecimal.ZERO, verifier.slashSubmitter(1L, null, new BigDecimal("100")));
    }

    @Test
    public void slashSubmitter_nullAmount_returnsZero() {
        assertEquals(BigDecimal.ZERO, verifier.slashSubmitter(1L, "s", null));
    }

    @Test
    public void slashSubmitter_nonPositiveAmount_returnsZero() {
        assertEquals(BigDecimal.ZERO, verifier.slashSubmitter(1L, "s", BigDecimal.ZERO));
        assertEquals(BigDecimal.ZERO, verifier.slashSubmitter(1L, "s", new BigDecimal("-1")));
    }

    @Test
    public void slashSubmitter_noSlashingService_simulatedSlash() {
        // 未注入 slashingService → 模拟罚没，返回全额
        BigDecimal slashed = verifier.slashSubmitter(1L, "submitter", new BigDecimal("1000"));
        assertEquals(new BigDecimal("1000"), slashed);
    }

    @Test
    public void slashSubmitter_withSlashingService_delegatesToService() {
        SlashingService mockSlashing = Mockito.mock(SlashingService.class);
        Mockito.when(mockSlashing.slash(eq("bad-sequencer"), eq(new BigDecimal("1000")), eq("FRAUD_PROVEN")))
                .thenReturn(new BigDecimal("800"));
        ReflectionTestUtils.setField(verifier, "slashingService", mockSlashing);

        BigDecimal actual = verifier.slashSubmitter(1L, "bad-sequencer", new BigDecimal("1000"));
        assertEquals(new BigDecimal("800"), actual);
        Mockito.verify(mockSlashing).slash("bad-sequencer", new BigDecimal("1000"), "FRAUD_PROVEN");
    }

    // ==================== rewardChallenger ====================

    @Test
    public void rewardChallenger_nullChallenger_noOp() {
        verifier.rewardChallenger(null, new BigDecimal("100"));
        // 无异常即通过
    }

    @Test
    public void rewardChallenger_nonPositiveReward_noOp() {
        verifier.stakeChallengeBond("c1", new BigDecimal("100"));
        verifier.rewardChallenger("c1", BigDecimal.ZERO);
        assertEquals(ChallengeBond.Status.STAKED, verifier.getChallengeBond("c1").getStatus());
    }

    @Test
    public void rewardChallenger_stakedBond_releasesBond() {
        verifier.stakeChallengeBond("c1", new BigDecimal("100"));
        verifier.rewardChallenger("c1", new BigDecimal("50"));
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("c1").getStatus());
    }

    @Test
    public void rewardChallenger_unknownChallenger_noOp() {
        verifier.rewardChallenger("unknown", new BigDecimal("100"));
        // 无异常即通过
    }

    // ==================== markChallenged ====================

    @Test
    public void markChallenged_nullBatch_noOp() {
        verifier.markChallenged(null);
    }

    @Test
    public void markChallenged_setsBatchAndTxStatus() {
        L2Transaction tx1 = makeTx("h1");
        L2Transaction tx2 = makeTx("h2");
        tx1.setStatus(L2TransactionStatus.INCLUDED);
        tx2.setStatus(L2TransactionStatus.PENDING);
        RollupBatch batch = buildBatch(1L, Arrays.asList(tx1, tx2));
        batch.setStatus(RollupBatchStatus.SUBMITTED);

        verifier.markChallenged(batch);
        assertEquals(RollupBatchStatus.CHALLENGED, batch.getStatus());
        assertEquals(L2TransactionStatus.REVERTED, tx1.getStatus());
        assertEquals(L2TransactionStatus.REVERTED, tx2.getStatus());
    }

    @Test
    public void markChallenged_nullTransactions_noOp() {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(1L);
        batch.setStatus(RollupBatchStatus.SUBMITTED);
        verifier.markChallenged(batch);
        assertEquals(RollupBatchStatus.CHALLENGED, batch.getStatus());
    }

    // ==================== finalizeBatch ====================

    @Test
    public void finalizeBatch_windowNotOver_returnsFalse() {
        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h")));
        verifier.onSubmit(batch, "s");
        assertFalse(verifier.finalizeBatch(1L));
    }

    @Test
    public void finalizeBatch_windowOver_marksVerified() throws InterruptedException {
        FraudProofVerifier shortWindow = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortWindow, "stateRootManager", stateRootManager);

        L2Transaction tx = makeTx("h");
        tx.setStatus(L2TransactionStatus.INCLUDED);
        RollupBatch batch = buildBatch(1L, Collections.singletonList(tx));
        shortWindow.onSubmit(batch, "s");
        Thread.sleep(20);

        assertTrue(shortWindow.finalizeBatch(1L));
        assertEquals(RollupBatchStatus.VERIFIED, batch.getStatus());
        assertEquals(L2TransactionStatus.CONFIRMED, tx.getStatus());
    }

    @Test
    public void finalizeBatch_windowOverButBatchMissing_returnsFalse() throws InterruptedException {
        FraudProofVerifier shortWindow = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortWindow, "stateRootManager", stateRootManager);
        shortWindow.onSubmit(1L);
        Thread.sleep(20);
        // batchStore 为空（onSubmit(long) 不存 batch）
        assertFalse(shortWindow.finalizeBatch(1L));
    }

    @Test
    public void finalizeBatch_alreadyChallenged_returnsFalse() throws InterruptedException {
        FraudProofVerifier shortWindow = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortWindow, "stateRootManager", stateRootManager);

        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h")));
        batch.setStatus(RollupBatchStatus.CHALLENGED);
        shortWindow.onSubmit(batch, "s");
        Thread.sleep(20);

        assertFalse(shortWindow.finalizeBatch(1L));
    }

    @Test
    public void finalizeBatch_triggersBridgeContract() throws InterruptedException {
        FraudProofVerifier shortWindow = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortWindow, "stateRootManager", stateRootManager);
        DefaultL2BridgeContract mockBridge = Mockito.mock(DefaultL2BridgeContract.class);
        ReflectionTestUtils.setField(shortWindow, "bridgeContract", mockBridge);

        RollupBatch batch = buildBatch(1L, Collections.singletonList(makeTx("h")));
        shortWindow.onSubmit(batch, "s");
        Thread.sleep(20);

        assertTrue(shortWindow.finalizeBatch(1L));
        Mockito.verify(mockBridge).markBatchVerified(1L);
        Mockito.verify(mockBridge).finalizeWithdrawsForBatch(1L);
    }

    // ==================== 动态挑战期策略 ====================

    @Test
    public void dynamicChallengePeriod_usesPolicyWhenInjected() {
        ChallengePeriodPolicy policy = Mockito.mock(ChallengePeriodPolicy.class);
        Mockito.when(policy.isChallengeWindowOver(eq(1L), any(Instant.class), any(BigDecimal.class)))
                .thenReturn(true);
        ReflectionTestUtils.setField(verifier, "challengePeriodPolicy", policy);

        verifier.onSubmit(1L);
        assertTrue(verifier.isChallengeWindowOver(1L));
        Mockito.verify(policy).isChallengeWindowOver(eq(1L), any(Instant.class), any(BigDecimal.class));
    }

    @Test
    public void dynamicChallengePeriod_policyReturnsFalse_windowOpen() {
        ChallengePeriodPolicy policy = Mockito.mock(ChallengePeriodPolicy.class);
        Mockito.when(policy.isChallengeWindowOver(anyLong(), any(Instant.class), any(BigDecimal.class)))
                .thenReturn(false);
        ReflectionTestUtils.setField(verifier, "challengePeriodPolicy", policy);

        verifier.onSubmit(1L);
        assertFalse(verifier.isChallengeWindowOver(1L));
    }

    @Test
    public void reportSuspiciousActivity_noPolicy_returnsFalse() {
        assertFalse(verifier.reportSuspiciousActivity(1L, "doubleRoot"));
    }

    @Test
    public void reportSuspiciousActivity_withPolicy_delegatesAndReturnsTrue() {
        ChallengePeriodPolicy policy = Mockito.mock(ChallengePeriodPolicy.class);
        ReflectionTestUtils.setField(verifier, "challengePeriodPolicy", policy);

        assertTrue(verifier.reportSuspiciousActivity(1L, "hiddenTx"));
        Mockito.verify(policy).reportSuspiciousActivity(1L, "hiddenTx");
    }

    // ==================== getters ====================

    @Test
    public void getChallengeWindow_returnsConfiguredDuration() {
        FraudProofVerifier v = new FraudProofVerifier(Duration.ofDays(3));
        assertEquals(Duration.ofDays(3), v.getChallengeWindow());
    }

    @Test
    public void getRewardRate_returnsConfiguredRate() {
        FraudProofVerifier v = new FraudProofVerifier(Duration.ofDays(7), new BigDecimal("0.3"));
        assertEquals(new BigDecimal("0.3"), v.getRewardRate());
    }

    @Test
    public void getBatch_unknownId_returnsNull() {
        assertNull(verifier.getBatch(999L));
    }

    @Test
    public void getSubmitter_unknownId_returnsNull() {
        assertNull(verifier.getSubmitter(999L));
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

    /** 准备批次：applyBatch + onSubmit，使 verifier 持有 batch 且 stateRootManager 有上下文。 */
    private void setupBatchAndSubmitter(long batchId, String submitter) {
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        RollupBatch batch = buildBatch(batchId, Arrays.asList(tx0, tx1));
        stateRootManager.applyBatch(batch);
        verifier.onSubmit(batch, submitter);
    }

    /** 构造一个针对 batchId 第 txIndex 步的有效欺诈证明（不设 challenger）。 */
    private FraudProof buildValidProofSetup(long batchId, int txIndex) {
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(batchId);
        assertNotNull("BatchContext must exist for batch " + batchId, ctx);
        MerkleProof mp = stateRootManager.getMerkleProof(batchId, txIndex);
        assertNotNull(mp);

        FraudProof proof = new FraudProof();
        proof.setBatchId(batchId);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(txIndex);
        proof.setTx(ctx.txs.get(txIndex));
        proof.setStateBefore(ctx.recursiveRoots.get(txIndex));
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter("fraudulentClaim_" + txIndex); // 故意错误 → 欺诈成立
        return proof;
    }

    /** 构造有效欺诈证明并设置 challenger。 */
    private FraudProof buildValidFraudProof(long batchId, int txIndex, String challenger) {
        FraudProof proof = buildValidProofSetup(batchId, txIndex);
        proof.setChallenger(challenger);
        return proof;
    }
}