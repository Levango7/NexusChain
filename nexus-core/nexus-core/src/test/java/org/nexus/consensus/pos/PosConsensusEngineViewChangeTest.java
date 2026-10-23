package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PLAN-028 view-change：槽位超时轮转推进测试。
 *
 * <p>背景（PLAN-027 多机实验缺陷 F3）：round-robin 按纯高度选提案者，
 * 指定槽位提案者死亡/被抑制时该高度永远无人出块 → 集群停链。
 * 修复：同一高度超过 {@code slot-timeout-seconds} 无块，
 * 按 {@code (height + round) % size} 顺序轮转至下一位提案者。</p>
 *
 * <p>测试通过直接拨 {@code viewStartedAtMillis} 表针，避免真实 sleep 拖慢套件。</p>
 */
class PosConsensusEngineViewChangeTest {

    private PosConsensusEngine engine;
    private static final long HEIGHT = 65L;

    @BeforeEach
    void setUp() {
        // 空构造生成随机签名密钥；advanceRoundIfSlotTimedOut 不触及其他依赖
        engine = new PosConsensusEngine();
    }

    @Test
    void disabledTimeoutNeverAdvances() {
        engine.slotTimeoutSeconds = 0L;
        long now = System.currentTimeMillis();
        engine.viewHeight = HEIGHT;
        engine.viewStartedAtMillis = now - 60_000L;   // 远超任何超时
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT));
        assertEquals(0, engine.viewRound, "禁用（timeout=0）必须保持 PLAN-007 原语义");
    }

    @Test
    void newHeightResetsView() {
        engine.slotTimeoutSeconds = 5L;
        engine.viewHeight = HEIGHT;
        engine.viewRound = 2;
        engine.viewStartedAtMillis = System.currentTimeMillis();
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT + 1),
                "高度推进 → 新视图周期从 round=0 开始");
        assertEquals(0, engine.viewRound);
    }

    @Test
    void withinTimeoutKeepsRound() {
        engine.slotTimeoutSeconds = 5L;
        engine.viewHeight = HEIGHT;
        engine.viewStartedAtMillis = System.currentTimeMillis();
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT));
        // 拨表：仅过 3 秒（< timeout=5s）
        engine.viewStartedAtMillis = System.currentTimeMillis() - 3_000L;
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT),
                "槽位空闲未达超时不应轮转");
        assertEquals(0, engine.viewRound);
    }

    @Test
    void beyondTimeoutAdvancesOneRoundPerPeriod() {
        engine.slotTimeoutSeconds = 5L;
        engine.viewHeight = HEIGHT;
        long now = System.currentTimeMillis();
        engine.viewStartedAtMillis = now;
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT));

        // 周期 1：6s → round 1
        engine.viewStartedAtMillis = now - 6_000L;
        assertEquals(1, engine.advanceRoundIfSlotTimedOut(HEIGHT));
        // 周期基准已推进 5s（拨 now-6s 后 +5s → now-1s），空闲仅 1s < 周期 → 保持 round=1
        assertEquals(1, engine.viewRound);
        assertEquals(1, engine.advanceRoundIfSlotTimedOut(HEIGHT));

        // 周期 2：再过 5s → round 2
        engine.viewStartedAtMillis = now - 10_000L;
        assertEquals(2, engine.advanceRoundIfSlotTimedOut(HEIGHT));
        assertEquals(2, engine.viewRound);
    }

    @Test
    void advancingThroughProposerSequenceRestoresLiveness() {
        engine.slotTimeoutSeconds = 1L;
        engine.viewHeight = HEIGHT;
        long now = System.currentTimeMillis();
        engine.viewStartedAtMillis = now;
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT));

        // 模拟死亡节点恢复路径：每周期拨表 2s，round 连续递增 →
        // 轮转依次覆盖所有验证人槽位（配合 PosProposerRoundRobinTest 的顺序断言，
        // 保证轮转最终到达可出块节点 → 链恢复活性）
        for (int expected = 1; expected <= 6; expected++) {
            engine.viewStartedAtMillis = now - expected * 2_000L;
            assertEquals(expected, engine.advanceRoundIfSlotTimedOut(HEIGHT),
                    "第 " + expected + " 个超时周期应推进到 round=" + expected);
        }
    }

    @Test
    void differentHeightsAdvanceIndependently() {
        engine.slotTimeoutSeconds = 5L;
        engine.viewHeight = HEIGHT;
        long now = System.currentTimeMillis();
        engine.viewStartedAtMillis = now;
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT));

        // 高度 66 到访 → 重置（即使旧高度早已超时）
        engine.viewStartedAtMillis = now - 100_000L;
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT + 1));

        // 回到旧高度（分叉/回滚场景）→ 再次视为新视图起点
        assertEquals(0, engine.advanceRoundIfSlotTimedOut(HEIGHT));
    }
}
