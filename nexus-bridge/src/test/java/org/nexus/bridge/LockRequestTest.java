package org.nexus.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LockRequest} 单元测试：覆盖构造、字段读写、equals/hashCode/toString。
 */
class LockRequestTest {

    @Test
    @DisplayName("全参数构造应正确设置所有字段并填充时间戳")
    void fullConstructor_setsAllFields() {
        long before = System.currentTimeMillis();
        LockRequest req = new LockRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xHash");
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
    @DisplayName("默认构造后通过 setter 设置字段")
    void defaultConstructor_withSetters() {
        LockRequest req = new LockRequest();
        req.setSourceChainId("polygon");
        req.setTargetChainId("ethereum");
        req.setAmount(500L);
        req.setUserAddress("0xU");
        req.setTargetAddress("0xT");
        req.setSourceTxHash("0xH");
        req.setTimestamp(12345L);
        req.setMemo("test memo");

        assertEquals("polygon", req.getSourceChainId());
        assertEquals("ethereum", req.getTargetChainId());
        assertEquals(500L, req.getAmount());
        assertEquals("0xU", req.getUserAddress());
        assertEquals("0xT", req.getTargetAddress());
        assertEquals("0xH", req.getSourceTxHash());
        assertEquals(12345L, req.getTimestamp());
        assertEquals("test memo", req.getMemo());
    }

    @Test
    @DisplayName("equals/hashCode 应基于所有业务字段")
    void equalsHashcode_basedOnAllFields() {
        LockRequest r1 = new LockRequest("a", "b", 100, "u", "t", "h");
        r1.setTimestamp(1000L);
        LockRequest r2 = new LockRequest("a", "b", 100, "u", "t", "h");
        r2.setTimestamp(1000L);
        LockRequest r3 = new LockRequest("a", "b", 200, "u", "t", "h");
        r3.setTimestamp(1000L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
        assertNotEquals(r1, "not a LockRequest");
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toString_containsKeyFields() {
        LockRequest req = new LockRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xHash");
        String str = req.toString();
        assertTrue(str.contains("ethereum"));
        assertTrue(str.contains("bsc"));
        assertTrue(str.contains("0xUser"));
        assertTrue(str.contains("0xHash"));
        assertTrue(str.startsWith("LockRequest{"));
    }
}