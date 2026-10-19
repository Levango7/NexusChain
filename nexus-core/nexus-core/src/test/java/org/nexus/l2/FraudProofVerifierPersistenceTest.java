package org.nexus.l2;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.challenge.ChallengeConflictResolver;
import org.nexus.l2.challenge.ChallengeConflictResult;
import org.nexus.l2.persistence.JdbcFraudProofStateStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 欺诈证明状态"进程重启不丢失"回归（PLAN-014 Phase 4）。
 *
 * <p>用同一 H2（PostgreSQL 兼容模式）库构造 verifier v1 → 写入状态 →
 * 构造新 verifier v2（模拟重启，{@code restoreState()}）→ 断言状态恢复：
 * 批次/提交者/挑战窗口、bond 状态、finalize 后的批次与交易状态、first-valid-wins 冲突状态。</p>
 */
class FraudProofVerifierPersistenceTest {

    private JdbcTemplate jdbc;
    private JdbcFraudProofStateStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fraud_verifier_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcFraudProofStateStore(jdbc);
        store.init();
    }

    /** 构造 verifier（模拟一次进程启动：注入 store/冲突解决器并执行恢复）。 */
    private FraudProofVerifier newVerifier(Duration window) {
        FraudProofVerifier verifier = new FraudProofVerifier(window);
        ChallengeConflictResolver resolver = new ChallengeConflictResolver();
        ReflectionTestUtils.setField(resolver, "stateStore", store);
        ReflectionTestUtils.setField(verifier, "stateRootManager", new StateRootManager());
        ReflectionTestUtils.setField(verifier, "stateStore", store);
        ReflectionTestUtils.setField(verifier, "conflictResolver", resolver);
        verifier.restoreState();
        return verifier;
    }

    private static ChallengeConflictResolver resolverOf(FraudProofVerifier verifier) {
        return (ChallengeConflictResolver) ReflectionTestUtils.getField(verifier, "conflictResolver");
    }

    @Test
    void batchAndSubmitterRestoredAfterRestart() {
        FraudProofVerifier v1 = newVerifier(Duration.ofDays(7));
        v1.onSubmit(sampleBatch(1L), "0xsubmitter");

        FraudProofVerifier v2 = newVerifier(Duration.ofDays(7));
        RollupBatch restored = v2.getBatch(1L);
        assertNotNull(restored);
        assertEquals(1L, restored.getBatchId());
        assertEquals("0xroot-1", restored.getStateRoot());
        assertEquals("0xsubmitter", v2.getSubmitter(1L));
        assertFalse(v2.isChallengeWindowOver(1L));
        assertEquals(1, restored.getTransactions().size());
        assertEquals("0xtx-1", restored.getTransactions().get(0).getTxHash());
    }

    @Test
    void bondStatusRestoredAfterRestart() {
        FraudProofVerifier v1 = newVerifier(Duration.ofDays(7));
        v1.stakeChallengeBond("0xchallenger", new BigDecimal("1000"));
        v1.stakeChallengeBond("0xreleased", new BigDecimal("500"));
        v1.releaseChallengeBond("0xreleased");

        FraudProofVerifier v2 = newVerifier(Duration.ofDays(7));
        ChallengeBond staked = v2.getChallengeBond("0xchallenger");
        assertNotNull(staked);
        assertEquals(ChallengeBond.Status.STAKED, staked.getStatus());
        assertEquals(new BigDecimal("1000"), staked.getAmount());

        ChallengeBond released = v2.getChallengeBond("0xreleased");
        assertNotNull(released);
        assertEquals(ChallengeBond.Status.RELEASED, released.getStatus());
    }

    @Test
    void finalizeStatusRestoredAfterRestart() throws InterruptedException {
        FraudProofVerifier v1 = newVerifier(Duration.ZERO);
        v1.onSubmit(sampleBatch(2L), "0xsubmitter");
        Thread.sleep(10);
        assertTrue(v1.finalizeBatch(2L));

        FraudProofVerifier v2 = newVerifier(Duration.ZERO);
        RollupBatch restored = v2.getBatch(2L);
        assertNotNull(restored);
        assertEquals(RollupBatchStatus.VERIFIED, restored.getStatus());
        assertEquals(L2TransactionStatus.CONFIRMED, restored.getTransactions().get(0).getStatus());
    }

    @Test
    void firstValidWinsConflictRestoredAfterRestart() {
        FraudProofVerifier v1 = newVerifier(Duration.ofDays(7));
        ChallengeConflictResolver r1 = resolverOf(v1);
        assertEquals(ChallengeConflictResult.FIRST_VALID,
                r1.resolveChallenge(3L, "0xc1", true, false));

        FraudProof proof = new FraudProof();
        proof.setBatchId(3L);
        proof.setChallenger("0xc1");
        proof.setPrevRoot("0xprev");
        r1.recordFirstValidProof(3L, proof);

        assertEquals(ChallengeConflictResult.DUPLICATE_AFTER_VALID,
                r1.resolveChallenge(3L, "0xc2", true, false));
        assertEquals(ChallengeConflictResult.INVALID_PROOF,
                r1.resolveChallenge(3L, "0xc3", false, false));

        FraudProofVerifier v2 = newVerifier(Duration.ofDays(7));
        ChallengeConflictResolver r2 = resolverOf(v2);
        assertTrue(r2.hasValidProof(3L));
        assertEquals("0xc1", r2.getFirstValidChallenger(3L));
        assertNotNull(r2.getFirstValidProof(3L));
        assertEquals("0xc1", r2.getFirstValidProof(3L).getChallenger());
        assertEquals(List.of("0xc1", "0xc2", "0xc3"), r2.getAllChallengers(3L));
        assertEquals(List.of("0xc2"), r2.getRefundedChallengers(3L));
        assertEquals(List.of("0xc3"), r2.getSlashedChallengers(3L));
    }

    @Test
    void clearRemovesPersistedConflict() {
        FraudProofVerifier v1 = newVerifier(Duration.ofDays(7));
        ChallengeConflictResolver r1 = resolverOf(v1);
        r1.resolveChallenge(4L, "0xc1", true, false);
        r1.clear(4L);

        FraudProofVerifier v2 = newVerifier(Duration.ofDays(7));
        ChallengeConflictResolver r2 = resolverOf(v2);
        assertFalse(r2.hasValidProof(4L));
        assertNull(r2.getFirstValidChallenger(4L));
        assertTrue(store.load().conflicts().isEmpty());
    }

    private static RollupBatch sampleBatch(long batchId) {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0xtx-" + batchId);
        tx.setBatchId(batchId);
        tx.setAmount(BigInteger.ONE);
        tx.setStatus(L2TransactionStatus.PENDING);

        RollupBatch batch = new RollupBatch();
        batch.setBatchId(batchId);
        batch.setStateRoot("0xroot-" + batchId);
        batch.setSubmitter("0xsubmitter");
        batch.setStatus(RollupBatchStatus.SUBMITTED);
        batch.setTransactions(List.of(tx));
        return batch;
    }
}
