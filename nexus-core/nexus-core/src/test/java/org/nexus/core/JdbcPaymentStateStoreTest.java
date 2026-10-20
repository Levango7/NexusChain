package org.nexus.core;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcPaymentStateStore} 往返测试。
 *
 * <p>回归目标：原实现使用 H2 专有 {@code MERGE ... KEY()}，H2 测试全绿但
 * PostgreSQL 生产路径会报 bad SQL grammar。本测试锁定可移植 upsert 的
 * "先插入、再更新、不产生重复行"语义。</p>
 */
class JdbcPaymentStateStoreTest {

    private JdbcTemplate jdbc;
    private JdbcPaymentStateStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:payment_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcPaymentStateStore(jdbc);
        store.init();
    }

    @Test
    void channelInsertThenUpdatePersistsWithoutDuplicate() {
        PaymentChannel channel = new PaymentChannel(
                "ch-1", "alice", "bob", 100, 200, 1, 0,
                PaymentChannel.State.OPEN, 10, 0, 100);

        store.putChannel("ch-1", channel);
        channel.setBalance1(150);
        channel.setNonce(2);
        store.putChannel("ch-1", channel);

        assertEquals(1, count("payment_channels", "channel_id", "ch-1"));
        assertEquals(150L, jdbc.queryForObject(
                "SELECT balance1 FROM payment_channels WHERE channel_id = 'ch-1'", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT nonce FROM payment_channels WHERE channel_id = 'ch-1'", Long.class));
        assertEquals(150L, store.getChannel("ch-1").getBalance1());
    }

    @Test
    void positionInsertThenUpdatePersistsWithoutDuplicate() {
        StableCoinPosition position = new StableCoinPosition("pos-1", "alice", 10);
        position.setCollateralAmount(1_000);
        position.setMintedAmount(500);
        position.setLastUpdateBlock(11);

        store.putPosition("pos-1", position);
        position.setCollateralAmount(2_000);
        store.putPosition("pos-1", position);

        assertEquals(1, count("stablecoin_positions", "position_id", "pos-1"));
        assertEquals(2_000L, jdbc.queryForObject(
                "SELECT collateral FROM stablecoin_positions WHERE position_id = 'pos-1'", Long.class));
        assertEquals(500L, jdbc.queryForObject(
                "SELECT minted FROM stablecoin_positions WHERE position_id = 'pos-1'", Long.class));
    }

    @Test
    void bridgeTxInsertThenUpdatePersistsWithoutDuplicate() {
        BridgeTransaction tx = new BridgeTransaction(
                "btx-1", "L1", "L2", 100, "bob", 2, 1_700_000_000L);
        tx.setValidators(List.of("v1", "v2"));

        store.putBridgeTx("btx-1", tx);
        tx.setAmount(250);
        tx.setState(BridgeTransaction.State.LOCKED);
        store.putBridgeTx("btx-1", tx);

        assertEquals(1, count("bridge_transactions", "bridge_tx_id", "btx-1"));
        assertEquals(250L, jdbc.queryForObject(
                "SELECT amount FROM bridge_transactions WHERE bridge_tx_id = 'btx-1'", Long.class));
        assertEquals("LOCKED", jdbc.queryForObject(
                "SELECT state FROM bridge_transactions WHERE bridge_tx_id = 'btx-1'", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT confirmations FROM bridge_transactions WHERE bridge_tx_id = 'btx-1'", Integer.class));
    }

    @Test
    void replayKeysAreIdempotentAndRecoveredAfterRestart() {
        store.putConsumedReplayKey("LOCK", "0xabc");
        store.putConsumedReplayKey("LOCK", "0xabc");

        assertEquals(1, count("bridge_replay_keys", "key_hex", "0xabc"));

        // 模拟重启：同一 DB 上新建 store（缓存为空，走 DB 回源）。
        JdbcPaymentStateStore restarted = new JdbcPaymentStateStore(jdbc);
        restarted.init();
        assertTrue(restarted.getAllConsumedReplayKeys("LOCK").contains("0xabc"));
    }

    private int count(String table, String keyColumn, String key) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + keyColumn + " = ?", Integer.class, key);
        return count == null ? 0 : count;
    }
}
