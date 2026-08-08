package org.nexus.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MintRequest} 单元测试：覆盖构造、签名计数、equals/hashCode/toString。
 */
class MintRequestTest {

    @Test
    @DisplayName("全参数构造应正确设置字段")
    void fullConstructor_setsFields() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        long before = System.currentTimeMillis();
        MintRequest req = new MintRequest("lock-1", sigs, "0xMinter", "bsc");
        long after = System.currentTimeMillis();

        assertEquals("lock-1", req.getLockTxId());
        assertEquals("0xMinter", req.getMinterAddress());
        assertEquals("bsc", req.getTargetChainId());
        assertEquals(2, req.getSignatureCount());
        assertTrue(req.getTimestamp() >= before && req.getTimestamp() <= after);
    }

    @Test
    @DisplayName("默认构造 + setter")
    void defaultConstructor_withSetters() {
        MintRequest req = new MintRequest();
        req.setLockTxId("lock-2");
        req.setMinterAddress("0xM");
        req.setTargetChainId("ethereum");
        req.setTimestamp(999L);

        assertEquals("lock-2", req.getLockTxId());
        assertEquals("0xM", req.getMinterAddress());
        assertEquals("ethereum", req.getTargetChainId());
        assertEquals(999L, req.getTimestamp());
    }

    @Test
    @DisplayName("getSignatureCount: null 签名返回 0")
    void getSignatureCount_nullReturnsZero() {
        MintRequest req = new MintRequest();
        req.setSignatures(null);
        assertEquals(0, req.getSignatureCount());
    }

    @Test
    @DisplayName("getSignatureCount: 空签名集合返回 0")
    void getSignatureCount_emptyReturnsZero() {
        MintRequest req = new MintRequest();
        req.setSignatures(new HashMap<>());
        assertEquals(0, req.getSignatureCount());
    }

    @Test
    @DisplayName("equals/hashCode 应基于业务字段")
    void equalsHashcode() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        MintRequest r1 = new MintRequest("lock-1", sigs, "0xM", "bsc");
        r1.setTimestamp(1000L);
        MintRequest r2 = new MintRequest("lock-1", sigs, "0xM", "bsc");
        r2.setTimestamp(1000L);
        MintRequest r3 = new MintRequest("lock-2", sigs, "0xM", "bsc");
        r3.setTimestamp(1000L);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
    }

    @Test
    @DisplayName("toString 应包含锁交易 ID 与签名数")
    void toString_containsKeyFields() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        MintRequest req = new MintRequest("lock-1", sigs, "0xM", "bsc");
        String str = req.toString();
        assertTrue(str.contains("lock-1"));
        assertTrue(str.contains("0xM"));
        assertTrue(str.contains("2 sigs"));
    }
}