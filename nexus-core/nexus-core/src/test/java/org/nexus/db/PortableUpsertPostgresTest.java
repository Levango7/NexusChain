package org.nexus.db;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.persistence.JdbcFinalityStateStore;
import org.nexus.consensus.finality.persistence.ValidatorSetPersistence;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.core.JdbcPaymentStateStore;
import org.nexus.core.payment.PaymentChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PostgreSQL 契约测试（Testcontainers）。
 *
 * <p><b>存在意义</b>：H2 支持 {@code MERGE ... KEY()}，所以"只在 H2 跑"的测试
 * 无法发现 PostgreSQL 语法不兼容。本测试在一次性 PG 容器中执行生产同款代码路径，
 * 锁定 upsert 不依赖任何一方专有语法。</p>
 *
 * <p>Docker 不可用时整类跳过（{@link Assumptions#assumeTrue}），不阻断本地无
 * Docker 或受限 CI 环境。</p>
 */
class PortableUpsertPostgresTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker 不可用，跳过真实 PostgreSQL 契约测试");

        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void helperUpsertAndInsertIgnoreWorkOnPostgres() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS t5_pg_kv ("
                + "id VARCHAR(32) PRIMARY KEY, v VARCHAR(32), n INT)");

        String update = "UPDATE t5_pg_kv SET v = ?, n = ? WHERE id = ?";
        String insert = "INSERT INTO t5_pg_kv(id, v, n) VALUES(?,?,?)";

        JdbcUpsert.upsert(jdbc, update, new Object[]{"alpha", 1, "k1"},
                insert, new Object[]{"k1", "alpha", 1});
        JdbcUpsert.upsert(jdbc, update, new Object[]{"beta", 2, "k1"},
                insert, new Object[]{"k1", "beta", 2});

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t5_pg_kv WHERE id = 'k1'", Integer.class));
        assertEquals("beta", jdbc.queryForObject(
                "SELECT v FROM t5_pg_kv WHERE id = 'k1'", String.class));

        JdbcUpsert.insertIgnore(jdbc, insert, new Object[]{"k2", "first", 1});
        JdbcUpsert.insertIgnore(jdbc, insert, new Object[]{"k2", "second", 2});
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t5_pg_kv WHERE id = 'k2'", Integer.class));
        assertEquals("first", jdbc.queryForObject(
                "SELECT v FROM t5_pg_kv WHERE id = 'k2'", String.class));
    }

    @Test
    void paymentStoreWritesAndUpdatesOnPostgres() {
        JdbcPaymentStateStore store = new JdbcPaymentStateStore(jdbc);
        store.init();

        PaymentChannel channel = new PaymentChannel(
                "pg-ch", "alice", "bob", 100, 200, 1, 0,
                PaymentChannel.State.OPEN, 10, 0, 100);
        store.putChannel("pg-ch", channel);
        channel.setBalance1(175);
        channel.setNonce(3);
        store.putChannel("pg-ch", channel);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_channels WHERE channel_id = 'pg-ch'", Integer.class));
        assertEquals(175L, jdbc.queryForObject(
                "SELECT balance1 FROM payment_channels WHERE channel_id = 'pg-ch'", Long.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT nonce FROM payment_channels WHERE channel_id = 'pg-ch'", Long.class));

        store.putConsumedReplayKey("LOCK", "0xpg");
        store.putConsumedReplayKey("LOCK", "0xpg");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM bridge_replay_keys WHERE key_hex = '0xpg'", Integer.class));
    }

    @Test
    void finalityStoreIsIdempotentAndReloadsOnPostgres() {
        JdbcFinalityStateStore store = new JdbcFinalityStateStore(jdbc);
        store.init();

        byte[] hash = new byte[]{4, 5, 6};
        store.recordVote(3, hash, "validator-1");
        store.recordVote(3, hash, "validator-1");
        store.markFinalized(3, hash);
        store.markFinalized(3, hash);

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM finality_votes", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM finality_checkpoints", Integer.class));

        JdbcFinalityStateStore restarted = new JdbcFinalityStateStore(jdbc);
        restarted.init();
        assertTrue(restarted.isFinalized(3, hash));
        assertEquals(Set.of("validator-1"), restarted.loadVoters(3, hash));
    }

    @Test
    void validatorSetUpsertUpdatesExistingRowOnPostgres() {
        ValidatorRegistry registry = new ValidatorRegistry();
        ValidatorSetPersistence persistence = new ValidatorSetPersistence(jdbc, registry);
        persistence.init();

        persistence.upsert("addr-1", "pubkey-1", new BigDecimal("100"));
        persistence.upsert("addr-1", "pubkey-2", new BigDecimal("250"));

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM validators_synced WHERE address = 'addr-1'", Integer.class));
        assertEquals("pubkey-2", jdbc.queryForObject(
                "SELECT public_key FROM validators_synced WHERE address = 'addr-1'", String.class));
        assertEquals("250", jdbc.queryForObject(
                "SELECT stake_amount FROM validators_synced WHERE address = 'addr-1'", String.class));

        // PLAN-001 切片 2/4：nonce 与 signed_payload 增量列在真实 PG 上可建、可读写
        // （init() 的 ALTER TABLE ADD COLUMN IF NOT EXISTS 已在上方执行）。
        persistence.upsert("addr-1", "pubkey-3", new BigDecimal("250"), 42L, "cGF5bG9hZA==");
        assertEquals(42L, persistence.lastNonce("addr-1"));
        assertEquals("cGF5bG9hZA==", persistence.loadSignedPayloads(10).get("addr-1"));
        // 无签名 upsert（COALESCE）不得清空已存载荷
        persistence.upsert("addr-1", "pubkey-4", new BigDecimal("250"), 43L);
        assertEquals("cGF5bG9hZA==", persistence.loadSignedPayloads(10).get("addr-1"));
        assertEquals(43L, persistence.lastNonce("addr-1"), "nonce 单调推进");
    }
}
