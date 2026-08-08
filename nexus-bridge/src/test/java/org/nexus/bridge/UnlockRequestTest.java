package org.nexus.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UnlockRequest} 单元测试：覆盖构造、签名计数、equals/hashCode/toString。
 */
class UnlockRequestTest {

    @Test
    @DisplayName("全参数构造应正确设置字段")
    void fullConstructor_setsFields() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        long before = System.currentTimeMillis();
        UnlockRequest req = new UnlockRequest("burn-1", sigs, "0xUnlocker", "ethereum");
        long after = System.currentTimeMillis();

        assertEquals("burn-1", req.getBurnTxId());
        assertEquals("0xUnlocker", req.getUnlockerAddress());
        assertEquals("ethereum", req.getSourceChainId());
        assertEquals(1, req.getSignatureCount());
        assertTrue(req.getTimestamp() >= before && req.getTimestamp() <= after);
    }

    @Test
    @DisplayName("默认构造 + setter")
    void defaultConstructor_withSetters() {
        UnlockRequest req = new UnlockRequest();
        req.setBurnTxId("burn-2");
        req.setUnlockerAddress("0xU");
        req.setSourceChainId("bsc");
        req.setTimestamp(42L);

        assertEquals("burn-2", req.getBurnTxId());
        assertEquals("0xU", req.getUnlockerAddress());
        assertEquals("bsc", req.getSourceChainId());
        assertEquals(42L, req.getTimestamp());
    }

    @Test
    @DisplayName("getSignatureCount: null 签名返回 0")
    void getSignatureCount_nullReturnsZero() {
        UnlockRequest req = new UnlockRequest();
        req.setSignatures(null);
        assertEquals(0, req.getSignatureCount());
    }

    @Test
    @DisplayName("equals/hashCode")
    void equalsHashcode() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        UnlockRequest r1 = new UnlockRequest("burn-1", sigs, "0xU", "eth");
        r1.setTimestamp(1000L);
        UnlockRequest r2 = new UnlockRequest("burn-1", sigs, "0xU", "eth");
        r2.setTimestamp(1000L);
        UnlockRequest r3 = new UnlockRequest("burn-2", sigs, "0xU", "eth");
        r3.setTimestamp(1000L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
    }

    @Test
    @DisplayName("toString 应包含销毁交易 ID 与签名数")
    void toString_containsKeyFields() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        UnlockRequest req = new UnlockRequest("burn-1", sigs, "0xU", "eth");
        String str = req.toString();
        assertTrue(str.contains("burn-1"));
        assertTrue(str.contains("0xU"));
        assertTrue(str.contains("1 sigs"));
    }
}