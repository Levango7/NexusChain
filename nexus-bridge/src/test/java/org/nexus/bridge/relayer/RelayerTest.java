package org.nexus.bridge.relayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Relayer} 实体单元测试：覆盖构造、字段读写。
 */
class RelayerTest {

    @Test
    @DisplayName("默认构造产生空对象")
    void defaultConstructor_emptyObject() {
        Relayer r = new Relayer();
        assertNull(r.getRelayerId());
        assertNull(r.getAddress());
        assertNull(r.getStake());
        assertEquals(0.0, r.getReputationScore());
        assertNull(r.getStatus());
    }

    @Test
    @DisplayName("全参数构造应正确设置所有字段")
    void fullConstructor_setsAllFields() {
        Relayer r = new Relayer("r-1", "0xabc", new BigDecimal("1000"), 95.0, RelayerStatus.ACTIVE);
        assertEquals("r-1", r.getRelayerId());
        assertEquals("0xabc", r.getAddress());
        assertEquals(0, new BigDecimal("1000").compareTo(r.getStake()));
        assertEquals(95.0, r.getReputationScore());
        assertEquals(RelayerStatus.ACTIVE, r.getStatus());
    }

    @Test
    @DisplayName("setter/getter 正确往返")
    void settersGetters_roundTrip() {
        Relayer r = new Relayer();
        r.setRelayerId("r-2");
        r.setAddress("0xdef");
        r.setStake(new BigDecimal("500"));
        r.setReputationScore(80.0);
        r.setStatus(RelayerStatus.SLASHED);

        assertEquals("r-2", r.getRelayerId());
        assertEquals("0xdef", r.getAddress());
        assertEquals(0, new BigDecimal("500").compareTo(r.getStake()));
        assertEquals(80.0, r.getReputationScore());
        assertEquals(RelayerStatus.SLASHED, r.getStatus());
    }

    @Test
    @DisplayName("RelayerStatus 枚举应包含 4 种状态")
    void relayerStatus_enumValues() {
        RelayerStatus[] statuses = RelayerStatus.values();
        assertEquals(4, statuses.length);
        assertTrue(java.util.Arrays.asList(statuses).containsAll(java.util.Arrays.asList(
                RelayerStatus.ACTIVE, RelayerStatus.INACTIVE,
                RelayerStatus.SLASHED, RelayerStatus.DEREGISTERED)));
    }
}