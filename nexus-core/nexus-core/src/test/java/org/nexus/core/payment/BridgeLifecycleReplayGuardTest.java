package org.nexus.core.payment;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgeLifecycleReplayGuard} 单元测试（v2.2.0）。
 *
 * <p>验证 BRIDGE_LOCK / BRIDGE_BURN 双向幂等标记、方向隔离、
 * 域分隔键派生与监控统计。</p>
 */
class BridgeLifecycleReplayGuardTest {

    private final BridgeLifecycleReplayGuard guard = new BridgeLifecycleReplayGuard();

    private static final String LOCK_HEX_1 =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String LOCK_HEX_2 =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String BURN_HEX_1 =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Test
    void markConsumedFirstTimeReturnsTrueThenFalse() {
        assertTrue(guard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, LOCK_HEX_1),
                "首次标记 LOCK 应成功");
        assertFalse(guard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, LOCK_HEX_1),
                "重复标记 LOCK 应返回 false（重放）");
        assertTrue(guard.isConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, LOCK_HEX_1));
    }

    @Test
    void lockAndBurnDirectionsAreIndependent() {
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, LOCK_HEX_1);
        assertEquals(1, guard.size(BridgeLifecycleReplayGuard.KIND_LOCK));
        assertEquals(0, guard.size(BridgeLifecycleReplayGuard.KIND_BURN));

        // 相同 hex 键在 BURN 方向不受 LOCK 消费影响
        assertFalse(guard.isConsumed(BridgeLifecycleReplayGuard.KIND_BURN, LOCK_HEX_1),
                "LOCK 的消费不应污染 BURN 方向");
        assertTrue(guard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, LOCK_HEX_1));
    }

    @Test
    void nullOrEmptyKeyIsFailClosed() {
        assertTrue(guard.isConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, null),
                "空键默认视为已消费（fail-closed）");
        assertFalse(guard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, null));
        assertFalse(guard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, ""));
    }

    @Test
    void duplicateMarkIncrementsRejectedCounter() {
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, BURN_HEX_1);
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, BURN_HEX_1);
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, BURN_HEX_1);
        assertEquals(2L, guard.rejected(BridgeLifecycleReplayGuard.KIND_BURN));
    }

    @Test
    void statsSnapshotContainsAllDirections() {
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, LOCK_HEX_1);
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, LOCK_HEX_2);
        guard.recordRejected(BridgeLifecycleReplayGuard.KIND_LOCK);
        guard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, BURN_HEX_1);

        Map<String, Object> stats = guard.stats();
        assertEquals(2, stats.get("consumedLock"));
        assertEquals(1, stats.get("consumedBurn"));
        assertEquals(1L, stats.get("rejectedLock"));
        assertEquals(0L, stats.get("rejectedBurn"));
        assertEquals(1L, stats.get("rejectedTotal"));
    }

    @Test
    void computeLockKeyIsDeterministicAndSemantic() {
        String k1 = BridgeLifecycleReplayGuard.computeLockKey("aabb", "eth", "0xabc123", 1000L);
        String k2 = BridgeLifecycleReplayGuard.computeLockKey("aabb", "eth", "0xabc123", 1000L);
        assertEquals(k1, k2, "相同语义字段应收敛到同一键");
        assertEquals(64, k1.length(), "应为 SHA-256 hex（64 字符）");

        String k3 = BridgeLifecycleReplayGuard.computeLockKey("aabb", "eth", "0xabc123", 999L);
        assertNotEquals(k1, k3, "金额不同则锁定意图不同");
        String k4 = BridgeLifecycleReplayGuard.computeLockKey("bbaa", "eth", "0xabc123", 1000L);
        assertNotEquals(k1, k4, "发起方不同则锁定意图不同");
    }

    @Test
    void computeBurnKeyIsDeterministicAndDifferentFromLockDomain() {
        String burn1 = BridgeLifecycleReplayGuard.computeBurnKey("aabb", "0011", 500L);
        String burn2 = BridgeLifecycleReplayGuard.computeBurnKey("aaab", "0011", 500L);
        assertEquals(burn1, burn1);
        String lock = BridgeLifecycleReplayGuard.computeLockKey("aabb", "x", "y", 500L);
        assertNotEquals(burn1, lock, "不同域名输入不同键");
    }
}