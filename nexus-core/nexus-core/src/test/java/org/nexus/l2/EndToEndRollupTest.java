package org.nexus.l2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.l2.challenge.ChallengeConflictResolver;
import org.nexus.l2.challenge.ChallengePeriodPolicy;
import org.nexus.consensus.pos.SlashingService;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * L2 Rollup 端到端测试：submit→challenge→rollback→finalize 全流程。
 *
 * <p>覆盖审计报告 §3.3 要求的 5 个端到端场景：</p>
 * <ol>
 *   <li>正常提交 + 最终确认（挑战窗口过期 → VERIFIED）</li>
 *   <li>挑战成功 + 回滚（有效欺诈证明 → CHALLENGED + REVERTED + 提交者罚没）</li>
 *   <li>挑战失败 + 罚没 bond（无效证明 → 挑战者 bond 罚没 + 批次保持 SUBMITTED）</li>
 *   <li>first-valid-wins 多挑战者冲突解决（A 首胜 / B 退还 / C 罚没）</li>
 *   <li>动态挑战期（高价值批次挑战窗口延长）</li>
 * </ol>
 *
 * <p>技术要点：真实 StateRootManager + FraudProofVerifier + OptimisticRollup；
 * L2BridgeContract / DefaultL2BridgeContract / SlashingService 用 Mock（内存模拟 L1）。</p>
 *
 * @since 1.3
 */
public class EndToEndRollupTest {

    private StateRootManager stateRootManager;
    private FraudProofVerifier verifier;
    private OptimisticRollup rollup;
    private L2BridgeContract bridge;

    @BeforeEach
    public void setUp() {
        stateRootManager = new StateRootManager();
        verifier = new FraudProofVerifier(Duration.ofDays(7));
        ReflectionTestUtils.setField(verifier, "stateRootManager", stateRootManager);

        rollup = new OptimisticRollup();
        ReflectionTestUtils.setField(rollup, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", verifier);

        // Mock L2BridgeContract（模拟 L1 桥合约，内存模式）
        bridge = Mockito.mock(L2BridgeContract.class);
        Mockito.when(bridge.submitStateRoot(anyLong(), anyString())).thenReturn(true);
        ReflectionTestUtils.setField(rollup, "bridge", bridge);
    }

    // ==================== 场景 1: 正常提交 + 最终确认 ====================

    /**
     * 端到端：submitBatch → 等待挑战窗口过期 → finalizeBatch → 状态 VERIFIED。
     *
     * <p>验证完整乐观确认流程：无挑战的批次在挑战窗口结束后被 finalize，
     * 批次状态变为 VERIFIED，交易状态变为 CONFIRMED，并触发桥合约提款解锁。</p>
     */
    @Test
    public void e2e_normalSubmit_thenFinalize_batchVerified() throws InterruptedException {
        // 用短挑战窗口的 verifier 模拟窗口过期
        FraudProofVerifier shortVerifier = new FraudProofVerifier(Duration.ofMillis(1));
        ReflectionTestUtils.setField(shortVerifier, "stateRootManager", stateRootManager);
        ReflectionTestUtils.setField(rollup, "fraudProofVerifier", shortVerifier);

        // Mock DefaultL2BridgeContract 用于验证 finalizeBatch 触发提款解锁
        DefaultL2BridgeContract mockBridgeContract = Mockito.mock(DefaultL2BridgeContract.class);
        ReflectionTestUtils.setField(shortVerifier, "bridgeContract", mockBridgeContract);

        // 1. 提交批次
        L2Transaction tx0 = makeTx("tx-0");
        L2Transaction tx1 = makeTx("tx-1");
        long batchId = rollup.submitBatch(Arrays.asList(tx0, tx1));
        assertEquals(RollupBatchStatus.SUBMITTED, shortVerifier.getBatch(batchId).getStatus());
        assertEquals(L2TransactionStatus.INCLUDED, tx0.getStatus());

        // 2. 等待挑战窗口过期
        Thread.sleep(20);
        assertTrue(shortVerifier.isChallengeWindowOver(batchId), "挑战窗口应已过期");

        // 3. finalizeBatch → 标记 VERIFIED + 触发桥合约
        assertTrue(shortVerifier.finalizeBatch(batchId));

        // 4. 验证批次状态 VERIFIED
        RollupBatch batch = shortVerifier.getBatch(batchId);
        assertEquals(RollupBatchStatus.VERIFIED, batch.getStatus());

        // 5. 验证交易状态 CONFIRMED
        assertEquals(L2TransactionStatus.CONFIRMED, tx0.getStatus());
        assertEquals(L2TransactionStatus.CONFIRMED, tx1.getStatus());

        // 6. 验证桥合约被触发（markBatchVerified + finalizeWithdrawsForBatch）
        Mockito.verify(mockBridgeContract).markBatchVerified(batchId);
        Mockito.verify(mockBridgeContract).finalizeWithdrawsForBatch(batchId);
    }

    // ==================== 场景 2: 挑战成功 + 回滚 + 提交者罚没 ====================

    /**
     * 端到端：submitBatch → 挑战者提交有效欺诈证明 → CHALLENGED + REVERTED + 提交者罚没。
     *
     * <p>验证欺诈挑战成功后的完整副作用：批次回滚、交易 REVERTED、
     * 提交者通过 SlashingService 被罚没、挑战者 bond 释放并获奖励。</p>
     */
    @Test
    public void e2e_challengeValid_batchChallengedAndSubmitterSlashed() {
        // Mock SlashingService（模拟 PoS 罚没）
        SlashingService mockSlashing = Mockito.mock(SlashingService.class);
        Mockito.when(mockSlashing.slash(eq("sequencer"), eq(new BigDecimal("1000")), eq("FRAUD_PROVEN")))
                .thenReturn(new BigDecimal("1000"));
        ReflectionTestUtils.setField(verifier, "slashingService", mockSlashing);

        // 1. 提交批次（submitter = "sequencer"）
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long batchId = rollup.submitBatch(Arrays.asList(tx0, tx1));

        // 2. 挑战者质押 bond
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));
        assertEquals(ChallengeBond.Status.STAKED, verifier.getChallengeBond("alice").getStatus());

        // 3. 构造并提交有效欺诈证明（claimedStateAfter 故意错误 → 欺诈成立）
        FraudProof proof = buildValidFraudProof(batchId, 0, "alice");
        assertTrue(rollup.challengeBatch(batchId, proof));

        // 4. 验证批次状态 CHALLENGED（回滚）
        RollupBatch batch = verifier.getBatch(batchId);
        assertEquals(RollupBatchStatus.CHALLENGED, batch.getStatus());

        // 5. 验证交易状态 REVERTED
        assertEquals(L2TransactionStatus.REVERTED, tx0.getStatus());
        assertEquals(L2TransactionStatus.REVERTED, tx1.getStatus());

        // 6. 验证提交者被罚没（SlashingService.slash 被调用）
        Mockito.verify(mockSlashing).slash("sequencer", new BigDecimal("1000"), "FRAUD_PROVEN");

        // 7. 验证挑战者 bond 释放（挑战成功 → rewardChallenger 释放 bond）
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("alice").getStatus());
    }

    // ==================== 场景 3: 挑战失败 + 罚没 bond ====================

    /**
     * 端到端：submitBatch → 挑战者提交无效证明 → 挑战者 bond 罚没 + 批次保持 SUBMITTED。
     *
     * <p>验证恶意挑战者提交无效欺诈证明（Merkle 验证失败）的处置：
     * 挑战者 bond 被罚没，批次状态不受影响，交易不被回滚。</p>
     */
    @Test
    public void e2e_challengeInvalid_challengerBondSlashedBatchIntact() {
        // 1. 提交批次
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long batchId = rollup.submitBatch(Arrays.asList(tx0, tx1));

        // 2. 恶意挑战者质押 bond
        verifier.stakeChallengeBond("malicious-challenger", new BigDecimal("1000"));
        assertEquals(ChallengeBond.Status.STAKED,
                verifier.getChallengeBond("malicious-challenger").getStatus());

        // 3. 构造无效欺诈证明（prevRoot 错误 → Merkle 验证失败 → proof invalid）
        FraudProof proof = buildValidFraudProof(batchId, 0, "malicious-challenger");
        proof.setPrevRoot("wrongRoot");

        // 4. 挑战失败
        boolean result = rollup.challengeBatch(batchId, proof);
        assertFalse(result, "无效证明的挑战应失败");

        // 5. 挑战者 bond 被罚没
        assertEquals(ChallengeBond.Status.SLASHED,
                verifier.getChallengeBond("malicious-challenger").getStatus());

        // 6. 批次保持 SUBMITTED（未受影响，未被回滚也未被确认）
        assertEquals(RollupBatchStatus.SUBMITTED, verifier.getBatch(batchId).getStatus());

        // 7. 交易状态保持 INCLUDED（未被回滚）
        assertEquals(L2TransactionStatus.INCLUDED, tx0.getStatus());
        assertEquals(L2TransactionStatus.INCLUDED, tx1.getStatus());
    }

    // ==================== 场景 4: first-valid-wins 多挑战者冲突解决 ====================

    /**
     * 端到端：first-valid-wins 多挑战者冲突解决。
     *
     * <p>三个挑战者对同一批次提交欺诈证明：</p>
     * <ul>
     *   <li>挑战者 A 有效证明 → FIRST_VALID，bond 释放，提交者罚没</li>
     *   <li>挑战者 B 也有效证明 → DUPLICATE_AFTER_VALID，bond 退还（不罚没）</li>
     *   <li>挑战者 C 无效证明 → INVALID_PROOF，bond 罚没</li>
     * </ul>
     * <p>验证提交者只被罚没一次（首胜时），后续有效挑战不重复触发 slashing。</p>
     */
    @Test
    public void e2e_firstValidWins_multipleChallengers() {
        // 注入真实 ChallengeConflictResolver 启用 first-valid-wins 冲突解决
        ChallengeConflictResolver resolver = new ChallengeConflictResolver();
        ReflectionTestUtils.setField(verifier, "conflictResolver", resolver);

        // Mock SlashingService（验证提交者只被罚没一次）
        SlashingService mockSlashing = Mockito.mock(SlashingService.class);
        Mockito.when(mockSlashing.slash(eq("sequencer"), eq(new BigDecimal("1000")), eq("FRAUD_PROVEN")))
                .thenReturn(new BigDecimal("1000"));
        ReflectionTestUtils.setField(verifier, "slashingService", mockSlashing);

        // 1. 提交批次
        L2Transaction tx0 = makeTx("h0");
        L2Transaction tx1 = makeTx("h1");
        long batchId = rollup.submitBatch(Arrays.asList(tx0, tx1));

        // 2. 三个挑战者分别质押 bond
        verifier.stakeChallengeBond("alice", new BigDecimal("500"));
        verifier.stakeChallengeBond("bob", new BigDecimal("500"));
        verifier.stakeChallengeBond("carol", new BigDecimal("500"));

        // 3. 挑战者 A 有效证明 → FIRST_VALID
        assertTrue(rollup.challengeBatch(batchId, buildValidFraudProof(batchId, 0, "alice")), "A 的有效挑战应被接受");
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("alice").getStatus());
        assertEquals(RollupBatchStatus.CHALLENGED, verifier.getBatch(batchId).getStatus());
        assertEquals(resolver.getFirstValidChallenger(batchId), "alice");
        assertTrue(resolver.hasValidProof(batchId));

        // 4. 挑战者 B 有效证明 → DUPLICATE_AFTER_VALID → bond 退还（不罚没）
        assertTrue(rollup.challengeBatch(batchId, buildValidFraudProof(batchId, 0, "bob")), "B 的重复有效挑战应被接受（bond 退还）");
        assertEquals(ChallengeBond.Status.RELEASED, verifier.getChallengeBond("bob").getStatus());

        // 5. 挑战者 C 无效证明 → INVALID_PROOF → bond 罚没
        FraudProof proofC = buildValidFraudProof(batchId, 0, "carol");
        proofC.setPrevRoot("wrongRoot"); // Merkle 验证失败 → proof invalid
        assertFalse(rollup.challengeBatch(batchId, proofC), "C 的无效挑战应被拒绝");
        assertEquals(ChallengeBond.Status.SLASHED, verifier.getChallengeBond("carol").getStatus());

        // 6. 提交者只被罚没一次（仅在 FIRST_VALID 时触发，DUPLICATE_AFTER_VALID 不重复 slash）
        Mockito.verify(mockSlashing, Mockito.times(1))
                .slash("sequencer", new BigDecimal("1000"), "FRAUD_PROVEN");

        // 7. 验证冲突解决器记录的挑战者顺序
        assertEquals(3, resolver.getAllChallengers(batchId).size());
        assertEquals(resolver.getFirstValidChallenger(batchId), "alice");
    }

    // ==================== 场景 5: 动态挑战期（高价值批次延长） ====================

    /**
     * 端到端：动态挑战期策略 —— 高价值批次挑战窗口延长。
     *
     * <p>注入 ChallengePeriodPolicy，配置短基础窗口 + 高价值延长：
     * 普通批次（金额低于阈值）使用基础窗口，高价值批次（金额超过阈值）
     * 按 tier 延长挑战期。验证等待一段时间后，普通批次窗口已过期而
     * 高价值批次窗口仍开放。</p>
     */
    @Test
    public void e2e_dynamicChallengePeriod_highValueBatchExtended() throws InterruptedException {
        // 配置动态挑战期策略
        // baseWindow=100ms, threshold=1000, extensionPerTier=200ms
        // 普通批次(amount=100) → 100ms；高价值批次(amount=10000, tier=1) → 100ms+200ms=300ms
        Duration baseWindow = Duration.ofMillis(100);
        BigDecimal highValueThreshold = new BigDecimal("1000");
        Duration extensionPerTier = Duration.ofMillis(200);
        ChallengePeriodPolicy policy = new ChallengePeriodPolicy(
                baseWindow, highValueThreshold, extensionPerTier, Duration.ofDays(30),
                Duration.ofDays(7), Duration.ofDays(14));
        ReflectionTestUtils.setField(verifier, "challengePeriodPolicy", policy);

        // 1. 提交普通批次（amount=100，低于阈值 → 挑战期 = baseWindow = 100ms）
        L2Transaction normalTx = makeTx("normal-tx");
        long normalBatchId = rollup.submitBatch(Collections.singletonList(normalTx));

        // 2. 提交高价值批次（amount=10000，超过阈值 → tier=1 → 挑战期 = 100ms + 200ms = 300ms）
        L2Transaction highValueTx = makeTx("high-value-tx");
        highValueTx.setAmount(BigInteger.valueOf(10000));
        long highValueBatchId = rollup.submitBatch(Collections.singletonList(highValueTx));

        // 3. 验证挑战期计算
        Duration normalPeriod = policy.computeChallengePeriod(normalBatchId, new BigDecimal("100"));
        Duration highValuePeriod = policy.computeChallengePeriod(highValueBatchId, new BigDecimal("10000"));
        assertEquals(baseWindow, normalPeriod, "普通批次挑战期应为基础窗口");
        assertEquals(baseWindow.plus(extensionPerTier), highValuePeriod, "高价值批次挑战期应为基础窗口+延长");

        // 4. 等待 150ms：普通窗口(100ms)已过期，高价值窗口(300ms)仍开放
        Thread.sleep(150);

        assertTrue(verifier.isChallengeWindowOver(normalBatchId), "普通批次挑战窗口应已过期");
        assertFalse(verifier.isChallengeWindowOver(highValueBatchId), "高价值批次挑战窗口应仍开放（动态延长生效）");
    }

    // ==================== 辅助方法（参考 FraudProofVerifierTest） ====================

    /**
     * 构造测试用 L2 交易。
     */
    private L2Transaction makeTx(String hash) {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash(hash);
        tx.setAmount(BigInteger.valueOf(100));
        return tx;
    }

    /**
     * 构造针对 batchId 第 txIndex 步的有效欺诈证明（claimedStateAfter 故意错误 → 欺诈成立）。
     *
     * <p>复用 {@link FraudProofVerifierTest} 的构造逻辑：
     * 从 StateRootManager.BatchContext 获取真实的 Merkle 证明、递归根链，
     * 设置 claimedStateAfter 为错误值使 verifyFraudProof 返回 true。</p>
     */
    private FraudProof buildValidFraudProof(long batchId, int txIndex, String challenger) {
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(batchId);
        assertNotNull(ctx, "BatchContext must exist for batch " + batchId);
        MerkleProof mp = stateRootManager.getMerkleProof(batchId, txIndex);
        assertNotNull(mp, "Merkle proof must exist for batch " + batchId + " txIndex " + txIndex);

        FraudProof proof = new FraudProof();
        proof.setBatchId(batchId);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(txIndex);
        proof.setTx(ctx.txs.get(txIndex));
        proof.setStateBefore(ctx.recursiveRoots.get(txIndex));
        proof.setMerkleProof(mp);
        proof.setClaimedStateAfter("fraudulentClaim_" + txIndex); // 故意错误 → 欺诈成立
        proof.setChallenger(challenger);
        return proof;
    }
}