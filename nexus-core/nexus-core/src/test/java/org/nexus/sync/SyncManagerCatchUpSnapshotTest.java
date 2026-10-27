package org.nexus.sync;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-033 切片 B：追赶快照（{@code GET /consensus/catch-up} 的数据源）测试。
 *
 * <p>锁定：gap 由对端最高高度推导；在途窗口的 stop/年龄；加速暂停标志；
 * 库异常时 local=0（观测不放大故障）。</p>
 */
class SyncManagerCatchUpSnapshotTest {

    private SyncManager manager() {
        SyncManager m = new SyncManager();
        ReflectionTestUtils.setField(m, "maxBlocksPerTransfer", 256);
        return m;
    }

    @Test
    void gapIsDerivedFromPeerHeight() {
        SyncManager m = manager();
        ReflectionTestUtils.setField(m, "knownMaxPeerHeight", 5000L);
        Map<String, Object> s = m.catchUpSnapshot();
        assertEquals(0L, s.get("localHeight"), "无库（stateDB=null）时 local 退化为 0，不抛异常");
        assertEquals(5000L, s.get("peerHeight"));
        assertEquals(5000L, s.get("gap"));
        assertEquals(false, s.get("windowRequested"));
        assertEquals(0L, s.get("windowAgeMillis"));
        assertEquals(false, s.get("accelerationPaused"));
        assertEquals(0L, s.get("stallCount"));
    }

    @Test
    void inFlightWindowReportsStopAndAge() {
        SyncManager m = manager();
        long now = System.currentTimeMillis();
        ReflectionTestUtils.setField(m, "knownMaxPeerHeight", 9000L);
        ReflectionTestUtils.setField(m, "catchUpWindowStop", 1234L);
        ReflectionTestUtils.setField(m, "catchUpIssuedAtMillis", now - 3000L);
        ReflectionTestUtils.setField(m, "catchUpRequestInFlight",
                new java.util.concurrent.atomic.AtomicBoolean(true));
        Map<String, Object> s = m.catchUpSnapshot();
        assertEquals(true, s.get("windowRequested"));
        assertEquals(1234L, s.get("windowStop"));
        long age = ((Number) s.get("windowAgeMillis")).longValue();
        assertTrue(age >= 3000L && age <= 8000L, "窗口年龄应在 3s~8s 区间，实际 " + age);
    }

    @Test
    void accelerationPausedFlagReflectsCooldown() {
        SyncManager m = manager();
        ReflectionTestUtils.setField(m, "catchUpPausedUntilMillis", System.currentTimeMillis() + 60_000L);
        ReflectionTestUtils.setField(m, "catchUpStallCount", new AtomicInteger(6));
        Map<String, Object> s = m.catchUpSnapshot();
        assertTrue((Boolean) s.get("accelerationPaused"), "暂停窗内应为 true");
        assertEquals(6L, s.get("stallCount"));
        // 暂停过期后自动恢复
        ReflectionTestUtils.setField(m, "catchUpPausedUntilMillis", 0L);
        assertFalse((Boolean) m.catchUpSnapshot().get("accelerationPaused"));
    }

    // ==================== PLAN-049：验证人注册表摘要（只观测，不剔除） ====================

    @Test
    @SuppressWarnings("unchecked")
    void withoutRegistryReportsDisabledNotZero() {
        Map<String, Object> s = manager().catchUpSnapshot();
        Map<String, Object> v = (Map<String, Object>) s.get("validators");
        assertEquals(false, v.get("enabled"), "注册表未装配必须是 enabled=false，不用 0 冒充");
        assertFalse(v.containsKey("active"), "未装配时不得出现计数字段");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registrySnapshotCountsActiveAndInactive() {
        SyncManager m = manager();
        org.nexus.consensus.pos.ValidatorRegistry registry =
                new org.nexus.consensus.pos.ValidatorRegistry(
                        new java.math.BigDecimal("100"), 100);
        registry.register("aaa", "11", new java.math.BigDecimal("1000"), 0.1);
        registry.register("bbb", "22", new java.math.BigDecimal("1000"), 0.1);
        registry.unregister("bbb"); // ACTIVE -> INACTIVE（"僵死/已退出"的可观测形态）
        ReflectionTestUtils.setField(m, "validatorRegistry", registry);

        Map<String, Object> v = (Map<String, Object>) m.catchUpSnapshot().get("validators");
        assertEquals(true, v.get("enabled"));
        assertEquals(2, v.get("total"));
        assertEquals(1, v.get("active"));
        assertEquals(1, v.get("inactive"));
        assertEquals(100, v.get("capacity"));
        assertEquals(java.util.List.of("aaa"), v.get("activeAddresses"));
        assertEquals(java.util.List.of("bbb"), v.get("inactiveAddresses"));
    }
}
