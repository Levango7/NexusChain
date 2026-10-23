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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 断言 2a：{@code restoreFromOutbox} 必须**循环捞取**直到一批取空，
     * 否则超出单批（64）的行永远不会被恢复。
     */
    @Test
    void restoreFromOutboxDrainsAllBatchesUntilEmpty() {
        ScriptedOutbox scripted = new ScriptedOutbox();
        scripted.addRows(TOTAL_ROWS);
        PendingBlocksManager manager = managerWith(scripted, /*lastConfirmedHeight=*/0L);

        manager.restoreFromOutbox();

        assertEquals(TOTAL_ROWS, scripted.rowsWritten.get(), "所有行都应被补写");
        assertEquals(0, scripted.pendingCount(), "outbox 应被捞空");
        // 64 + 6 + 1（空批收尾）= 3 次
        assertEquals(3, scripted.loadPendingCalls.get(),
                "必须循环捞取：64 一批 + 6 一批 + 空批收尾");
        assertTrue(isRestoreDone(manager), "捞空后应置 outboxRestored");
    }

    /**
     * 断言 2b：存储不可用时**不得**把"空列表"当成"已恢复完"。
     *
     * <p>{@code JdbcPendingBlockOutbox.loadPending} 在库异常时吞掉异常返回空列表，
     * 原实现因此把"PG 还没起来"判成"没有待恢复区块"并永久置位——
     * 这正是 PLAN-017 的核心场景（PG 停 → 重启 → PG 未恢复 → 永不补写）。</p>
     */
    @Test
    void restoreFromOutboxDoesNotMarkDoneWhenStorageUnavailable() {
        ScriptedOutbox scripted = new ScriptedOutbox();
        scripted.addRows(TOTAL_ROWS);
        scripted.unavailable = true;            // pendingCount() -> -1，loadPending -> 空列表
        PendingBlocksManager manager = managerWith(scripted, /*lastConfirmedHeight=*/0L);

        manager.restoreFromOutbox();
        assertFalse(isRestoreDone(manager), "存储不可用时不得标记为已恢复完");

        // 下一轮调度必须还能再试（没有一次性置位）
        manager.restoreFromOutbox();
        assertFalse(isRestoreDone(manager), "存储不可用时不得标记为已恢复完（第二轮）");
        assertTrue(scripted.loadPendingCalls.get() >= 2, "未标记完成时应持续重试");
    }

    /**
     * 断言 2c：本批没有任何行被补写掉时必须停止循环（防对同一批无限重试），
     * 且不置位，保留下一轮重试机会。
     */
    @Test
    void restoreFromOutboxStopsWhenNoProgressInsteadOfSpinning() {
        ScriptedOutbox scripted = new ScriptedOutbox();
        scripted.addRows(TOTAL_ROWS);
        // lastConfirmed 高于所有块高度 ⇒ processChain 全部跳过 ⇒ 没有任何行被移除
        PendingBlocksManager manager = managerWith(scripted, /*lastConfirmedHeight=*/TOTAL_ROWS);

        manager.restoreFromOutbox();

        assertEquals(1, scripted.loadPendingCalls.get(), "无进展时必须停止，不得对同一批重试");
        assertEquals(TOTAL_ROWS, scripted.pendingCount(), "一行都不应被移除");
        assertFalse(isRestoreDone(manager), "无进展时不得标记为已恢复完");
    }

    /** 读 manager 的 outboxRestored 标志（包内私有字段，用反射）。 */
    private static boolean isRestoreDone(PendingBlocksManager manager) {
        Object v = ReflectionTestUtils.getField(manager, "outboxRestored");
        return Boolean.TRUE.equals(v);
    }

    /** 构造一个装配了脚本化 outbox 的 manager；{@code lastConfirmedHeight} 控制哪些块被跳过。 */
    private PendingBlocksManager managerWith(PendingBlockOutbox outbox, long lastConfirmedHeight) {
        PendingBlocksManager manager = new PendingBlocksManager();
        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);

        when(stateDB.getLastConfirmed()).thenReturn(block(lastConfirmedHeight));
        when(stateDB.hasBlock(any())).thenReturn(false);
        when(stateDB.hasBlockInCache(any())).thenReturn(false);
        when(bc.hasBlock(any())).thenReturn(false);
        when(rule.validateBlock(any())).thenReturn(Result.SUCCESS);
        when(merkleRule.validateBlock(any())).thenReturn(Result.SUCCESS);

        ReflectionTestUtils.setField(manager, "stateDB", stateDB);
        ReflectionTestUtils.setField(manager, "bc", bc);
        ReflectionTestUtils.setField(manager, "rule", rule);
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);
        ReflectionTestUtils.setField(manager, "blockOutbox", outbox);
        return manager;
    }

    /**
     * 可编排的桩 outbox：模拟"一批最多 limit 行 + 显式 remove 才移除"的真实语义，
     * 并可切换为"存储不可用"（{@code pendingCount() -> -1}、{@code loadPending -> 空列表}）。
     */
    private static final class ScriptedOutbox implements PendingBlockOutbox {

        private final List<Block> rows = new ArrayList<>();
        private final AtomicInteger loadPendingCalls = new AtomicInteger(0);
        private final AtomicInteger rowsWritten = new AtomicInteger(0);
        private volatile boolean unavailable;

        private void addRows(int n) {
            for (int h = 1; h <= n; h++) {
                Block b = new Block();
                b.nHeight = h;
                b.nTime = 1700000000L + h;
                b.hashPrevBlock = new byte[]{1, 2, 3, 4};
                b.hashMerkleRoot = new byte[]{9, 9, 9};
                b.hashMerkleState = new byte[]{8, 8, 8};
                b.hashMerkleIncubate = new byte[]{7, 7, 7};
                b.body = new ArrayList<>();
                rows.add(b);
            }
        }

        @Override
        public List<Block> loadPending(int limit) {
            loadPendingCalls.incrementAndGet();
            if (unavailable) {
                return List.of();      // 与 JdbcPendingBlockOutbox 一致：吞掉异常返回空
            }
            int end = Math.min(limit, rows.size());
            return new ArrayList<>(rows.subList(0, end));
        }

        @Override
        public void remove(Block block) {
            if (rows.removeIf(b -> b.nHeight == block.nHeight)) {
                rowsWritten.incrementAndGet();
            }
        }

        @Override
        public int pendingCount() {
            return unavailable ? -1 : rows.size();
        }
    }
}
