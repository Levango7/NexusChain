package org.nexus.core.pending;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.encoding.JSONEncodeDecoder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcPendingBlockOutbox} 往返测试（PLAN-019）。
 *
 * <p>回归目标：</p>
 * <ul>
 *   <li>待补写区块必须**跨进程存活**（表里的载荷可解码回区块）；</li>
 *   <li>同区块重复入队不产生重复行（幂等）；</li>
 *   <li>补写成功后行被清掉；</li>
 *   <li>没有 JdbcTemplate 时组件**禁用**而不是启动失败。</li>
 * </ul>
 */
class JdbcPendingBlockOutboxTest {

    private JdbcTemplate jdbc;
    private JSONEncodeDecoder codec;
    private JdbcPendingBlockOutbox outbox;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:blockoutbox_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        codec = new JSONEncodeDecoder();
        outbox = new JdbcPendingBlockOutbox(jdbc, codec, codec);
        outbox.init();
    }

    private Block block(long height) {
        Block b = new Block();
        b.nHeight = height;
        b.nTime = 1700000000L + height;
        b.hashPrevBlock = new byte[]{1, 2, 3, 4};
        b.hashMerkleRoot = new byte[]{9, 9, 9};
        b.hashMerkleState = new byte[]{8, 8, 8};
        b.hashMerkleIncubate = new byte[]{7, 7, 7};
        b.body = new java.util.ArrayList<>();
        return b;
    }

    @Test
    void pendingBlockSurvivesRestartAndCanBeDecodedBack() {
        Block b = block(42);
        outbox.enqueue(b);

        // 模拟重启：同一个库上新建 store。
        JdbcPendingBlockOutbox restarted = new JdbcPendingBlockOutbox(jdbc, codec, codec);
        restarted.init();

        List<Block> pending = restarted.loadPending(10);
        assertEquals(1, pending.size(), "重启后必须能捞回待补写区块");
        assertEquals(42, pending.get(0).nHeight);
        assertEquals(1, restarted.pendingCount());
    }

    @Test
    void repeatedEnqueueIsIdempotent() {
        outbox.enqueue(block(7));
        outbox.enqueue(block(7));
        assertEquals(1, outbox.pendingCount(), "同一区块重复入队不得产生重复行");
    }

    @Test
    void removeClearsTheRow() {
        Block b = block(9);
        outbox.enqueue(b);
        outbox.remove(b);
        assertEquals(0, outbox.pendingCount());
        assertTrue(outbox.loadPending(10).isEmpty());
    }

    @Test
    void markFailureUpdatesAttempts() {
        Block b = block(11);
        outbox.enqueue(b);
        outbox.markFailure(b, "boom");
        List<Block> pending = outbox.loadPending(10);
        assertEquals(1, pending.size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempts FROM pending_block_outbox WHERE block_hash = ?",
                Integer.class, org.apache.commons.codec.binary.Hex.encodeHexString(b.getHash())));
    }

    @Test
    void disabledWithoutJdbc() {
        JdbcPendingBlockOutbox disabled = new JdbcPendingBlockOutbox(null, codec, codec);
        disabled.init();
        assertEquals(-1, disabled.pendingCount(), "未装配时 pendingCount 必须是 -1（未知）");
        assertTrue(disabled.loadPending(10).isEmpty());
        // 不得抛异常
        disabled.enqueue(block(1));
        disabled.remove(block(1));
    }

    @Test
    void oldestCreatedAtAndMaxAttemptsReflectPendingRows() {
        long before = System.currentTimeMillis();
        Block b1 = block(21);
        Block b2 = block(22);
        outbox.enqueue(b1);
        outbox.enqueue(b2);
        outbox.markFailure(b2, "boom");

        long oldest = outbox.oldestCreatedAt();
        assertTrue(oldest >= before && oldest <= System.currentTimeMillis(),
                "最老行创建时刻应位于入队前后，实际 " + oldest);
        assertEquals(1, outbox.maxAttempts(), "b2 失败一次 → 最大 attempts=1");
    }

    @Test
    void oldestCreatedAtAndMaxAttemptsReturnMinusOneWhenEmptyOrDisabled() {
        assertEquals(-1L, outbox.oldestCreatedAt(), "无行 → -1");
        assertEquals(-1, outbox.maxAttempts(), "无行 → -1");

        JdbcPendingBlockOutbox disabled = new JdbcPendingBlockOutbox(null, codec, codec);
        disabled.init();
        assertEquals(-1L, disabled.oldestCreatedAt(), "未装配 → -1");
        assertEquals(-1, disabled.maxAttempts(), "未装配 → -1");
    }
}
