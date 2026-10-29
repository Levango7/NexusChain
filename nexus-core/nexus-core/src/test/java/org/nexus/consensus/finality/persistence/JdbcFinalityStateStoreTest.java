package org.nexus.consensus.finality.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcFinalityStateStore} 往返测试。
 *
 * <p>回归目标：原实现使用 H2 专有 {@code MERGE ... KEY()}，PostgreSQL
 * 生产路径会失败；同时验证重复投票/重复终局不产生重复行，以及重启后状态可从库恢复。</p>
 */
class JdbcFinalityStateStoreTest {

    private JdbcTemplate jdbc;
    private JdbcFinalityStateStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:finality_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcFinalityStateStore(jdbc);
        store.init();
    }

    @Test
    void repeatedVoteAndFinalityAreIdempotent() {
        byte[] hash = new byte[]{1, 2, 3};
        store.recordVote(5, hash, "validator-1");
        store.recordVote(5, hash, "validator-1");
        store.markFinalized(5, hash);
        store.markFinalized(5, hash);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM finality_votes", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM finality_checkpoints", Integer.class));
        assertTrue(store.isFinalized(5, hash));
        assertEquals(Set.of("validator-1"), store.loadVoters(5, hash));
    }

    @Test
    void thirtyTwoByteCheckpointFitsSchemaAndReloads() {
        // 生产 checkpoint 是 32 字节；旧实现用 Arrays.toString 编码后超过 VARCHAR(128)，
        // PostgreSQL 报 "value too long for type character varying(128)"。本测试锁定修复。
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (i * 7 - 100);
        }
        store.recordVote(11, hash, "validator-32");
        store.markFinalized(11, hash);

        JdbcFinalityStateStore restarted = new JdbcFinalityStateStore(jdbc);
        restarted.init();
        assertTrue(restarted.isFinalized(11, hash));
        assertEquals(Set.of("validator-32"), restarted.loadVoters(11, hash));
    }

    @Test
    void restartReloadsVotesAndFinalityFromDatabase() {
        byte[] hash = new byte[]{9, 8, 7};
        store.recordVote(7, hash, "validator-a");
        store.recordVote(7, hash, "validator-b");
        store.markFinalized(7, hash);

        // 模拟重启：同一 DB 上新建 store，init 走 loadAll 重建缓存。
        JdbcFinalityStateStore restarted = new JdbcFinalityStateStore(jdbc);
        restarted.init();

        assertTrue(restarted.isFinalized(7, hash));
        assertEquals(Set.of("validator-a", "validator-b"), restarted.loadVoters(7, hash));
    }

    @Test
    void voteSignatureIsPersistedAndReloaded() {
        // PLAN-018：签名与公钥必须随投票落库，否则重启后恢复不出 Vote 对象，
        // 聚合验签无票可验（检查点永远补不了最终化）。
        byte[] hash = new byte[]{4, 5, 6};
        byte[] signature = new byte[64];
        byte[] publicKey = new byte[32];
        for (int i = 0; i < signature.length; i++) {
            signature[i] = (byte) (i + 1);
        }
        for (int i = 0; i < publicKey.length; i++) {
            publicKey[i] = (byte) (i + 3);
        }
        store.recordVote(3, hash, "validator-sig", signature, publicKey);

        JdbcFinalityStateStore restarted = new JdbcFinalityStateStore(jdbc);
        restarted.init();

        Map<String, List<org.nexus.consensus.finality.Vote>> restored = restarted.loadAllVoteObjects();
        assertEquals(1, restored.size(), "重启后必须能恢复出带签名的 Vote 对象");
        List<org.nexus.consensus.finality.Vote> votes = restored.values().iterator().next();
        assertEquals(1, votes.size());
        org.nexus.consensus.finality.Vote vote = votes.get(0);
        assertEquals("validator-sig", vote.getValidatorAddress());
        assertArrayEquals(signature, vote.getSignature());
        assertArrayEquals(publicKey, vote.getPublicKeyBytes());
    }

    /**
     * PLAN-057 第 11 处回归（store 级）：**升序分页必须按 epoch 完整交付**。
     *
     * <p>第八轮测试床实测（1/1026 票缺口）：响应页 {@code ORDER BY epoch ASC LIMIT 200}
     * 会在某个 epoch 中间截断，请求方按标记的 {@code servedTo+1} 前进 ⇒ 被截断的票
     * 永远不再被请求。修法：页必须把"哨兵 epoch"整体带入。</p>
     *
     * <p>本例：epoch 1 有 4 票、epoch 2 有 1 票，limit=2：
     * 正确行为 = 第 1 页回 epoch 1 的全部 4 票（虽超限），第 2 页回 epoch 2。</p>
     */
    @Test
    void ascendingPageNeverSplitsAnEpoch() {
        for (byte cp = 1; cp <= 4; cp++) {
            store.recordVote(1, new byte[]{cp}, "v" + cp, sig(), pk());
        }
        store.recordVote(2, new byte[]{9}, "v9", sig(), pk());

        java.util.List<org.nexus.consensus.finality.Vote> first =
                store.loadVoteObjectsAscending(1, 2);
        assertEquals(4, first.size(), "第 11 处：epoch 1 的 4 票必须整页带回（虽超 limit=2）");
        assertTrue(first.stream().allMatch(v -> v.getEpoch() == 1));
        assertTrue(first.stream().map(v -> v.getValidatorAddress()).filter(java.util.Objects::nonNull)
                        .allMatch(a -> a.startsWith("v")), "回带的必须是带签名的 Vote 对象");

        java.util.List<org.nexus.consensus.finality.Vote> second =
                store.loadVoteObjectsAscending(2, 2);
        assertEquals(1, second.size(), "第 2 页应只剩 epoch 2 的那 1 票");
        assertEquals(2, second.get(0).getEpoch());
    }

    /**
     * PLAN-057 第 12 处回归（store 级）：**行数不足 limit+1 时不得越界、不得变空页**。
     *
     * <p>第九轮测试床实测：旧实现直接 {@code rows.get(cap)}，当库内带签名票少于
     * limit+1 时抛 IndexOutOfBounds，被 catch 吞成空页 ⇒ 响应端回 count=0 标记 ⇒
     * 请求方判"对端无货"提前终止分页（A.log 209 条 WARN，D 连续 12 轮空标记，
     * 整条爬升退化成 margin 涓流）。本例库内只有 3 票、limit=200。</p>
     */
    @Test
    void shortHistoryMustNotBeSwallowedIntoEmptyPage() {
        for (byte cp = 1; cp <= 3; cp++) {
            store.recordVote(1, new byte[]{cp}, "s" + cp, sig(), pk());
        }
        // 3 票远小于 limit=200 ⇒ 必须原样返回 3 票（而不是越界成空页）。
        java.util.List<org.nexus.consensus.finality.Vote> page =
                store.loadVoteObjectsAscending(1, 200);
        assertEquals(3, page.size(), "第 12 处：短页必须原样返回，不能变空页");
        assertEquals(1, page.get(0).getEpoch());
    }

    private static byte[] sig() {
        byte[] s = new byte[64];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (i + 1);
        }
        return s;
    }

    private static byte[] pk() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) {
            k[i] = (byte) (i + 3);
        }
        return k;
    }

    @Test
    void outboxRoundTripsAndSurvivesRestart() {
        // PLAN-018：写库意图必须能跨进程存活。
        byte[] hash = new byte[]{7, 7, 7};
        byte[] signature = new byte[64];
        FinalityOutboxEntry entry = new FinalityOutboxEntry(4, hash, "validator-outbox",
                signature, new byte[32], FinalityVoteOrigin.RECEIVED, 1234L, 0, "boom");
        store.enqueuePendingVote(entry);

        JdbcFinalityStateStore restarted = new JdbcFinalityStateStore(jdbc);
        restarted.init();
        List<FinalityOutboxEntry> rows = restarted.loadPendingVotes(10);
        assertEquals(1, rows.size(), "重启后必须能捞回 outbox 行");
        FinalityOutboxEntry row = rows.get(0);
        assertEquals(4, row.getEpoch());
        assertEquals("validator-outbox", row.getValidatorAddress());
        assertEquals(FinalityVoteOrigin.RECEIVED, row.getOrigin());
        assertArrayEquals(signature, row.getSignature());

        restarted.markPendingVoteFailure(row, "still failing");
        List<FinalityOutboxEntry> afterFailure = restarted.loadPendingVotes(10);
        assertEquals(1, afterFailure.size());
        assertEquals(1, afterFailure.get(0).getAttempts());
        assertEquals("still failing", afterFailure.get(0).getLastError());

        restarted.removePendingVote(row);
        assertEquals(0, restarted.loadPendingVotes(10).size(), "补写成功后必须能清掉 outbox 行");
    }

    @Test
    void initIsIdempotentOnLegacySchema() {
        // 模拟"老库"：finality_votes 已存在但没有 signature/public_key 列。
        // init() 里的 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 必须把它补齐，
        // 否则新代码写签名会直接 BadSqlGrammarException。
        jdbc.execute("DROP TABLE finality_votes");
        jdbc.execute("CREATE TABLE finality_votes (epoch BIGINT NOT NULL, "
                + "checkpoint_hash VARCHAR(128) NOT NULL, validator VARCHAR(128) NOT NULL, "
                + "PRIMARY KEY (epoch, checkpoint_hash, validator))");

        JdbcFinalityStateStore upgraded = new JdbcFinalityStateStore(jdbc);
        upgraded.init();

        byte[] hash = new byte[]{1, 1, 1};
        upgraded.recordVote(2, hash, "validator-legacy", new byte[64], new byte[32]);
        assertEquals(Set.of("validator-legacy"), upgraded.loadVoters(2, hash));
        assertEquals(1, upgraded.loadAllVoteObjects().size());
    }
}
