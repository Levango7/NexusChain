package org.nexus.core;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.pending.JdbcPendingBlockOutbox;
import org.nexus.core.pending.PendingBlockOutbox;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.nexus.encoding.JSONEncodeDecoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 持久化区块 outbox 的「一次捞取上限」回归（PLAN-019 落地后的缺口）。
 *
 * <p><b>缺陷陈述</b>：{@link PendingBlocksManager#restoreFromOutbox()} 每次只调
 * {@code blockOutbox.loadPending(64)}，并把 {@code outboxRestored} 无条件置为
 * {@code true}（{@code PendingBlocksManager.java:266/:268/:271}）；
 * 而 {@link JdbcPendingBlockOutbox#loadPending(int)} 是
 * {@code ORDER BY height ASC LIMIT <cap>}，无分页、无游标。
 * ⇒ 重启时 outbox 有 <b>N > 64</b> 条待补写区块时，第 65 条及以后
 * <b>永远不会被这条路径恢复</b>（只能等 P2P 同步重新拉）。</p>
 *
 * <p><b>本测试的两个断言互补，合起来即上述结论</b>：</p>
 * <ol>
 *   <li>{@link #loadPendingCapsAtLimitAndLeavesTheRest()} —— 单次捞取硬上限 64，余行留在表里；</li>
 *   <li>{@link #restoreFromOutboxFetchesOnlyOnceSoTailRowsAreNeverRetried()} ——
 *       {@code restoreFromOutbox} 不循环，整个进程生命周期只捞一次。</li>
 * </ol>
 *
 * <p>影响分级依据（为何不是 P1）：{@code PendingBlockOutbox} 的 javadoc 自述区块是
 * 「别人也能提供的数据」，重启后 P2P 同步能重新拉回；持久化 outbox 的收益是省一次
 * 同步风暴，不是数据不可恢复。⇒ 后果是恢复变慢 + {@code pendingCount()} 长期虚高。</p>
 *
 * <p>本测试为**纯新增回归**，不改任何产品代码；缺陷修复后断言 1 的期望值需随之更新
 * （例如改为循环捞取后，本应用例应改写为「捞完为止」）。</p>
 */
public class PendingBlocksManagerOutboxRestoreCapTest {

    /** 触发缺陷所需的行数：> loadPending(64) 的硬上限。 */
    private static final int TOTAL_ROWS = 70;
    private static final int BATCH_LIMIT = 64;

    private JdbcTemplate jdbc;
    private JSONEncodeDecoder codec;
    private JdbcPendingBlockOutbox outbox;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:outboxcap_" + UUID.randomUUID().toString().replace("-", "")
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
        b.body = new ArrayList<>();
        return b;
    }

    /**
     * 断言 1：单次 {@code loadPending(64)} 只返回 64 条，剩余行留在表里。
     *
     * <p>这是缺陷的「上限」半边——没有分页，所以一次捞不完。</p>
     */
    @Test
    void loadPendingCapsAtLimitAndLeavesTheRest() {
        for (int h = 1; h <= TOTAL_ROWS; h++) {
            outbox.enqueue(block(h));
        }
        assertEquals(TOTAL_ROWS, outbox.pendingCount(), "入队应全部落库");

        List<Block> firstBatch = outbox.loadPending(BATCH_LIMIT);

        assertEquals(BATCH_LIMIT, firstBatch.size(),
                "loadPending(64) 必须只返回 64 条（硬上限，无分页）");
        assertEquals(TOTAL_ROWS, outbox.pendingCount(),
                "loadPending 不应移除任何行——清除由补写成功后显式 remove 完成");
        // 按高度升序：被留下的正是最高的 6 条
        long maxReturned = firstBatch.stream().mapToLong(Block::getnHeight).max().orElse(-1);
        assertEquals(BATCH_LIMIT, maxReturned, "应按 height ASC 取前 64 条");
    }

    /**
     * 断言 2：{@code restoreFromOutbox} 整个进程生命周期只捞一次，
     * 所以「一次捞不完」的尾部行永远不会被重试。
     *
     * <p>这是缺陷的「不循环」半边。用一个记录调用次数的桩 outbox，
     * 不依赖真实数据库，也不依赖 {@link BlocksCache} 的成链行为。</p>
     */
    @Test
    void restoreFromOutboxFetchesOnlyOnceSoTailRowsAreNeverRetried() {
        RecordingOutbox recording = new RecordingOutbox();
        PendingBlocksManager manager = new PendingBlocksManager();

        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);

        // 让 processChain 把所有块当「已确认过」直接跳过：本用例只关心捞取次数，
        // 不关心写链路径（写链路径由 PendingBlocksManagerStorageRetryTest 覆盖）。
        when(stateDB.getLastConfirmed()).thenReturn(block(TOTAL_ROWS));
        when(stateDB.hasBlock(any())).thenReturn(false);
        when(stateDB.hasBlockInCache(any())).thenReturn(false);
        when(bc.hasBlock(any())).thenReturn(false);
        when(rule.validateBlock(any())).thenReturn(Result.SUCCESS);
        when(merkleRule.validateBlock(any())).thenReturn(Result.SUCCESS);

        ReflectionTestUtils.setField(manager, "stateDB", stateDB);
        ReflectionTestUtils.setField(manager, "bc", bc);
        ReflectionTestUtils.setField(manager, "rule", rule);
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);
        ReflectionTestUtils.setField(manager, "blockOutbox", recording);

        manager.restoreFromOutbox();
        manager.restoreFromOutbox();
        manager.restoreFromOutbox();

        assertEquals(1, recording.loadPendingCalls.get(),
                "restoreFromOutbox 必须只捞一次（outboxRestored 是一次性标志）"
                        + "——重复调用不会再捞，所以超出单批的行永远不会被恢复");
        assertTrue(recording.loadedOnce, "桩应至少被取用过一次");
        assertNotNull(recording.lastLimit);
        assertEquals(BATCH_LIMIT, recording.lastLimit,
                "restoreFromOutbox 应以 64 为限调用 loadPending");
    }

    /**
     * 记录 {@code loadPending} 调用次数与上限的桩 outbox。
     *
     * <p>返回 64 条「已确认过」的块（高度 ≤ lastConfirmed），使
     * {@code addPendingBlocks} 走完整条路径但不产生任何写库/移除动作。</p>
     */
    private static final class RecordingOutbox implements PendingBlockOutbox {

        private final AtomicInteger loadPendingCalls = new AtomicInteger(0);
        private volatile Integer lastLimit;
        private volatile boolean loadedOnce;

        @Override
        public List<Block> loadPending(int limit) {
            loadPendingCalls.incrementAndGet();
            lastLimit = limit;
            loadedOnce = true;
            List<Block> out = new ArrayList<>();
            for (int h = 1; h <= limit; h++) {
                Block b = new Block();
                b.nHeight = h;
                b.nTime = 1700000000L + h;
                b.hashPrevBlock = new byte[]{1, 2, 3, 4};
                b.hashMerkleRoot = new byte[]{9, 9, 9};
                b.hashMerkleState = new byte[]{8, 8, 8};
                b.hashMerkleIncubate = new byte[]{7, 7, 7};
                b.body = new ArrayList<>();
                out.add(b);
            }
            return out;
        }

        @Override
        public int pendingCount() {
            return TOTAL_ROWS - BATCH_LIMIT;
        }
    }
}
