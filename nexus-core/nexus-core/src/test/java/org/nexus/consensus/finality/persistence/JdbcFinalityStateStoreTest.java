package org.nexus.consensus.finality.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

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
}
