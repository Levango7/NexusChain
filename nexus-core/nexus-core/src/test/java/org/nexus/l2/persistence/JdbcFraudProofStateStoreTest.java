package org.nexus.l2.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.l2.ChallengeBond;
import org.nexus.l2.FraudProof;
import org.nexus.l2.L2Transaction;
import org.nexus.l2.L2TransactionStatus;
import org.nexus.l2.MerkleProof;
import org.nexus.l2.RollupBatch;
import org.nexus.l2.RollupBatchStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcFraudProofStateStore} 往返测试（PLAN-014 Phase 4）。
 *
 * <p>使用 H2 PostgreSQL 兼容模式（与 TestConfig 同款配置），覆盖：
 * 批次 JSON 往返（含 byte[] / BigInteger / 枚举 / 嵌套交易）、upsert 幂等、
 * bond 往返、冲突状态（含 FraudProof/MerkleProof 嵌套）往返、删除、schema 版本登记。</p>
 */
class JdbcFraudProofStateStoreTest {

    private JdbcTemplate jdbc;
    private JdbcFraudProofStateStore store;

    @BeforeEach
    void setUp() {
        jdbc = newJdbcTemplate();
        store = new JdbcFraudProofStateStore(jdbc);
        store.init();
    }

    private static JdbcTemplate newJdbcTemplate() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:fraud_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return new JdbcTemplate(dataSource);
    }

    /** 模拟重启：同一 DB 上新建 store 实例（init 走 SchemaMeta 同版本分支）。 */
    private JdbcFraudProofStateStore restartedStore() {
        JdbcFraudProofStateStore restarted = new JdbcFraudProofStateStore(jdbc);
        restarted.init();
        return restarted;
    }

    @Test
    void schemaMetaIsRegistered() {
        Integer version = jdbc.queryForObject(
                "SELECT version FROM nexus_schema_meta WHERE store = ?",
                Integer.class, "l2_fraud_state");
        assertEquals(JdbcFraudProofStateStore.SCHEMA_VERSION, version);
    }

    @Test
    void batchRoundTripSurvivesRestart() {
        RollupBatch batch = sampleBatch(7L);
        Instant submitTime = Instant.ofEpochMilli(1_700_000_000_000L);
        store.saveBatch(batch, "0xsubmitter", submitTime);

        FraudProofStateStore.Snapshot snapshot = restartedStore().load();

        assertEquals(1, snapshot.batches().size());
        FraudProofStateStore.BatchRecord record = snapshot.batches().get(0);
        assertEquals(7L, record.batch().getBatchId());
        assertEquals("0xroot", record.batch().getStateRoot());
        assertEquals(RollupBatchStatus.SUBMITTED, record.batch().getStatus());
        assertEquals("0xsubmitter", record.submitter());
        assertEquals(submitTime, record.submitTime());

        assertEquals(1, record.batch().getTransactions().size());
        L2Transaction tx = record.batch().getTransactions().get(0);
        assertEquals("0xtx-hash", tx.getTxHash());
        assertArrayEquals(new byte[]{1, 2, 3}, tx.getRawTx());
        assertEquals(new BigInteger("12345678901234567890"), tx.getAmount());
        assertEquals(L2TransactionStatus.INCLUDED, tx.getStatus());
        assertEquals("0xsender", tx.getSender());
        assertEquals(42L, tx.getNonce());
        assertEquals(new BigInteger("7"), tx.getPriorityFee());
        assertEquals(21_000L, tx.getGasLimit());
        assertEquals("0xrecipient", tx.getRecipient());
    }

    @Test
    void saveBatchIsUpsert() {
        RollupBatch batch = sampleBatch(8L);
        store.saveBatch(batch, "0xs", Instant.ofEpochMilli(1_000L));

        batch.setStatus(RollupBatchStatus.VERIFIED);
        batch.getTransactions().get(0).setStatus(L2TransactionStatus.CONFIRMED);
        store.saveBatch(batch, "0xs", Instant.ofEpochMilli(2_000L));

        FraudProofStateStore.Snapshot snapshot = restartedStore().load();
        assertEquals(1, snapshot.batches().size());
        assertEquals(RollupBatchStatus.VERIFIED, snapshot.batches().get(0).batch().getStatus());
        assertEquals(L2TransactionStatus.CONFIRMED,
                snapshot.batches().get(0).batch().getTransactions().get(0).getStatus());
        assertEquals(Instant.ofEpochMilli(2_000L), snapshot.batches().get(0).submitTime());
    }

    @Test
    void bondRoundTripAndStatusUpdate() {
        ChallengeBond bond = new ChallengeBond("0xchallenger", new BigDecimal("1000"));
        store.saveBond(bond);

        bond.setStatus(ChallengeBond.Status.RELEASED);
        store.saveBond(bond);

        FraudProofStateStore.Snapshot snapshot = restartedStore().load();
        assertEquals(1, snapshot.bonds().size());
        ChallengeBond restored = snapshot.bonds().get(0);
        assertEquals("0xchallenger", restored.getChallengerId());
        assertEquals(new BigDecimal("1000"), restored.getAmount());
        assertEquals(ChallengeBond.Status.RELEASED, restored.getStatus());
        assertEquals(bond.getStakeTime(), restored.getStakeTime());
    }

    @Test
    void conflictRoundTripWithNestedProof() {
        FraudProof proof = new FraudProof();
        proof.setBatchId(9L);
        proof.setPrevRoot("0xprev");
        proof.setTxIndex(2);
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0xinner-tx");
        tx.setBatchId(9L);
        proof.setTx(tx);
        proof.setStateBefore("0xstate-before");
        proof.setStateAfter("0xstate-after");
        proof.setClaimedStateAfter("0xclaimed-after");
        proof.setChallenger("0xc1");
        proof.setChallengeBond(new BigDecimal("500"));
        proof.setMerkleProof(new MerkleProof("key-1", "value-1", 1,
                List.of("sibling-a", "sibling-b"), List.of(0, 1)));

        store.saveConflict(new FraudProofStateStore.ConflictRecord(9L, "0xc1", proof,
                List.of("0xc1", "0xc2"), List.of("0xc2"), List.of()));

        FraudProofStateStore.Snapshot snapshot = restartedStore().load();
        assertEquals(1, snapshot.conflicts().size());
        FraudProofStateStore.ConflictRecord record = snapshot.conflicts().get(0);
        assertEquals(9L, record.batchId());
        assertEquals("0xc1", record.firstValidChallenger());
        assertEquals(List.of("0xc1", "0xc2"), record.allChallengers());
        assertEquals(List.of("0xc2"), record.refundedChallengers());
        assertTrue(record.slashedChallengers().isEmpty());

        FraudProof restoredProof = record.firstValidProof();
        assertEquals(9L, restoredProof.getBatchId());
        assertEquals("0xprev", restoredProof.getPrevRoot());
        assertEquals(2, restoredProof.getTxIndex());
        assertEquals("0xinner-tx", restoredProof.getTx().getTxHash());
        assertEquals("0xclaimed-after", restoredProof.getClaimedStateAfter());
        assertEquals(new BigDecimal("500"), restoredProof.getChallengeBond());
        assertEquals(List.of("sibling-a", "sibling-b"), restoredProof.getMerkleProof().getSiblings());
        assertEquals(List.of(0, 1), restoredProof.getMerkleProof().getDirections());
    }

    @Test
    void deleteConflictRemovesRow() {
        store.saveConflict(new FraudProofStateStore.ConflictRecord(10L, null, null,
                List.of("0xc1"), List.of(), List.of()));
        store.deleteConflict(10L);
        assertTrue(restartedStore().load().conflicts().isEmpty());
    }

    private static RollupBatch sampleBatch(long batchId) {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0xtx-hash");
        tx.setBatchId(batchId);
        tx.setRawTx(new byte[]{1, 2, 3});
        tx.setAmount(new BigInteger("12345678901234567890"));
        tx.setStatus(L2TransactionStatus.INCLUDED);
        tx.setSender("0xsender");
        tx.setNonce(42L);
        tx.setPriorityFee(new BigInteger("7"));
        tx.setGasLimit(21_000L);
        tx.setRecipient("0xrecipient");

        RollupBatch batch = new RollupBatch();
        batch.setBatchId(batchId);
        batch.setStateRoot("0xroot");
        batch.setSubmitter("0xsubmitter");
        batch.setStatus(RollupBatchStatus.SUBMITTED);
        batch.setTransactions(List.of(tx));
        return batch;
    }
}
