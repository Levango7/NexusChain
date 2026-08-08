package org.nexus.bridge.relayer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RelayRequest} 实体单元测试：覆盖构造、字段读写、状态枚举。
 */
class RelayRequestTest {

    @Test
    @DisplayName("默认构造产生空对象")
    void defaultConstructor_emptyObject() {
        RelayRequest req = new RelayRequest();
        assertNull(req.getRequestId());
        assertNull(req.getSourceChain());
        assertNull(req.getTargetChain());
        assertNull(req.getSourceTxHash());
        assertNull(req.getAmount());
        assertNull(req.getStatus());
        assertNull(req.getAssignedRelayerId());
    }

    @Test
    @DisplayName("setter/getter 正确往返")
    void settersGetters_roundTrip() {
        RelayRequest req = new RelayRequest();
        req.setRequestId("RELAY-001");
        req.setSourceChain("ethereum");
        req.setTargetChain("bsc");
        req.setSourceTxHash("0xHash");
        req.setAmount(new BigDecimal("1000"));
        req.setStatus(RelayRequestStatus.RELAYING);
        req.setAssignedRelayerId("relayer-1");

        assertEquals("RELAY-001", req.getRequestId());
        assertEquals("ethereum", req.getSourceChain());
        assertEquals("bsc", req.getTargetChain());
        assertEquals("0xHash", req.getSourceTxHash());
        assertEquals(0, new BigDecimal("1000").compareTo(req.getAmount()));
        assertEquals(RelayRequestStatus.RELAYING, req.getStatus());
        assertEquals("relayer-1", req.getAssignedRelayerId());
    }

    @Test
    @DisplayName("RelayRequestStatus 枚举应包含 4 种状态")
    void relayRequestStatus_enumValues() {
        RelayRequestStatus[] statuses = RelayRequestStatus.values();
        assertEquals(4, statuses.length);
        assertTrue(java.util.Arrays.asList(statuses).containsAll(java.util.Arrays.asList(
                RelayRequestStatus.PENDING, RelayRequestStatus.RELAYING,
                RelayRequestStatus.COMPLETED, RelayRequestStatus.FAILED)));
    }
}