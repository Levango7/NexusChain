package org.nexus.analytics.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryChainMetricsProvider} 单元测试。
 *
 * <p>覆盖默认值、setter 注入与 null 安全。
 */
class InMemoryChainMetricsProviderTest {

    private InMemoryChainMetricsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InMemoryChainMetricsProvider();
    }

    @Test
    void collectNodeHealth_default_shouldContainOnlineSyncLagPeerCount() {
        Map<String, Object> m = provider.collectNodeHealth();

        assertEquals(true, m.get("online"));
        assertEquals(0, m.get("syncLag"));
        assertEquals(12, m.get("peerCount"));
    }

    @Test
    void collectBlockPropagation_default_shouldContainPropagationP95() {
        Map<String, Object> m = provider.collectBlockPropagation();

        assertNotNull(m.get("propagationP95Ms"));
        assertNotNull(m.get("lastBlockHeight"));
    }

    @Test
    void collectMempool_default_shouldContainPendingCount() {
        Map<String, Object> m = provider.collectMempool();

        assertEquals(0, m.get("pendingCount"));
        assertEquals(0.0, m.get("feeP50"));
    }

    @Test
    void setNodeHealth_shouldOverrideDefault() {
        Map<String, Object> custom = new HashMap<>();
        custom.put("online", false);
        custom.put("syncLag", 999);
        custom.put("peerCount", 1);

        provider.setNodeHealth(custom);

        Map<String, Object> m = provider.collectNodeHealth();
        assertEquals(false, m.get("online"));
        assertEquals(999, m.get("syncLag"));
        assertEquals(1, m.get("peerCount"));
    }

    @Test
    void setBlockPropagation_shouldOverrideDefault() {
        Map<String, Object> custom = new HashMap<>();
        custom.put("propagationP95Ms", 1234L);

        provider.setBlockPropagation(custom);

        assertEquals(1234L, provider.collectBlockPropagation().get("propagationP95Ms"));
    }

    @Test
    void setMempool_shouldOverrideDefault() {
        Map<String, Object> custom = new HashMap<>();
        custom.put("pendingCount", 500);

        provider.setMempool(custom);

        assertEquals(500, provider.collectMempool().get("pendingCount"));
    }

    @Test
    void setNodeHealth_null_shouldKeepDefault() {
        provider.setNodeHealth(null);

        assertNotNull(provider.collectNodeHealth().get("online"));
    }

    @Test
    void setBlockPropagation_null_shouldKeepDefault() {
        provider.setBlockPropagation(null);

        assertNotNull(provider.collectBlockPropagation().get("propagationP95Ms"));
    }

    @Test
    void setMempool_null_shouldKeepDefault() {
        provider.setMempool(null);

        assertNotNull(provider.collectMempool().get("pendingCount"));
    }

    @Test
    void collect_shouldReturnDefensiveCopy() {
        Map<String, Object> original = provider.collectNodeHealth();
        original.put("online", "tampered");

        // 再次采集应不受影响
        assertEquals(true, provider.collectNodeHealth().get("online"));
    }
}