package org.nexus.sync;

import org.junit.jupiter.api.Test;
import org.nexus.core.Block;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PLAN-036：GetBlocks 响应体积裁剪（capBySize）单测。
 *
 * <p>背景：旧实现把超限响应拆多片、只把第 1 片走入站通道，其余片 `server.dial`
 * 回拨——星型拓扑下回拨必失败，请求方永远只收 1 片、窗口原地反复请求
 * （多机实测 B 卡 13955）。修复：按体积裁剪、单消息回送，尾部留给下一轮窗口。</p>
 */
class SyncManagerGetBlocksSizeCapTest {

    private final Block b1 = mock(Block.class);
    private final Block b2 = mock(Block.class);
    private final Block b3 = mock(Block.class);

    private ToIntFunction<Block> sizes(int... sz) {
        return b -> sz[List.of(b1, b2, b3).indexOf(b)];
    }

    @Test
    void keepsAllBlocksWhenWithinBudget() {
        List<Block> out = SyncManager.capBySize(List.of(b1, b2, b3), sizes(100, 200, 300), 1000);
        assertEquals(List.of(b1, b2, b3), out);
    }

    @Test
    void cutsToPrefixWhenBudgetExceeded() {
        // 预算 250：b1(100) 可留；再加 b2(200) ⇒ 300 > 250 ⇒ 截断
        List<Block> out = SyncManager.capBySize(List.of(b1, b2, b3), sizes(100, 200, 300), 250);
        assertEquals(List.of(b1), out, "应保持顺序、在放不下的前一个截断");
    }

    @Test
    void cutSemanticsAreExactAtBoundary() {
        // 100 + 200 = 300 ≤ 400 → 第二块可留；加第三块 600 > 400 → 截断
        List<Block> out = SyncManager.capBySize(List.of(b1, b2, b3), sizes(100, 200, 300), 400);
        assertEquals(List.of(b1, b2), out);
        // 边界：恰好等于预算也算放得下
        List<Block> exact = SyncManager.capBySize(List.of(b1, b2), sizes(200, 200), 400);
        assertEquals(List.of(b1, b2), exact);
    }

    @Test
    void keepsFirstBlockEvenWhenItAloneExceedsBudget() {
        // 首块必留（单块超限由调用方告警并跳过服务，不能返回空让调用方误判"没有块"）
        List<Block> out = SyncManager.capBySize(List.of(b1, b2), sizes(10_000, 1), 100);
        assertEquals(List.of(b1), out);
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(SyncManager.capBySize(List.of(), sizes(), 100).isEmpty());
    }
}
