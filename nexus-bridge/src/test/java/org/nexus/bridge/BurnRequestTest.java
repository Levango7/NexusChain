package org.nexus.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BurnRequest} 单元测试：覆盖构造、字段读写、equals/hashCode/toString。
 */
class BurnRequestTest {

    @Test
    @DisplayName("全参数构造应正确设置字段")
    void fullConstructor_setsFields() {
        long before = System.currentTimeMillis();
        BurnRequest req = new BurnRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xHash");
        long after = System.currentTimeMillis();

        assertEquals("ethereum", req.getSourceChainId());
        assertEquals("bsc", req.getTargetChainId());
        assertEquals(1000L, req.getAmount());
        assertEquals("0xUser", req.getUserAddress());
        assertEquals("0xTarget", req.getTargetAddress());
        assertEquals("0xHash", req.getSourceTxHash());
        assertTrue(req.getTimestamp() >= before && req.getTimestamp() <= after);
    }

    @Test
    @DisplayName("默认构造 + setter")
    void defaultConstructor_withSetters() {
        BurnRequest req = new BurnRequest();
        req.setSourceChainId("bsc");
        req.setTargetChainId("ethereum");
        req.setAmount(200L);
        req.setUserAddress("0xU");
        req.setTargetAddress("0xT");
        req.setSourceTxHash("0xH");
        req.setTimestamp(555L);

        assertEquals("bsc", req.getSourceChainId());
        assertEquals("ethereum", req.getTargetChainId());
        assertEquals(200L, req.getAmount());
        assertEquals(555L, req.getTimestamp());
    }

    @Test
    @DisplayName("equals/hashCode")
    void equalsHashcode() {
        BurnRequest r1 = new BurnRequest("a", "b", 100, "u", "t", "h");
        r1.setTimestamp(1000L);
        BurnRequest r2 = new BurnRequest("a", "b", 100, "u", "t", "h");
        r2.setTimestamp(1000L);
        BurnRequest r3 = new BurnRequest("a", "b", 999, "u", "t", "h");
        r3.setTimestamp(1000L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
        assertNotEquals(r1, "string");
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toString_containsKeyFields() {
        BurnRequest req = new BurnRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xHash");
        String str = req.toString();
        assertTrue(str.contains("ethereum"));
        assertTrue(str.contains("bsc"));
        assertTrue(str.contains("0xUser"));
        assertTrue(str.startsWith("BurnRequest{"));
    }
}