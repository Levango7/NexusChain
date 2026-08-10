package org.nexus.settlement.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TransactionRequest} 单元测试。
 * <p>覆盖构造、getter/setter、equals/hashCode/toString 与默认值。</p>
 */
class TransactionRequestTest {

    @Test
    void defaultConstructor_shouldDefaultAssetToNEX() {
        TransactionRequest req = new TransactionRequest();
        assertEquals("NEX", req.getAsset());
        assertNull(req.getType());
        assertNull(req.getFromAddress());
        assertNull(req.getToAddress());
        assertNull(req.getAmount());
        assertNull(req.getMemo());
        assertNull(req.getRequestId());
    }

    @Test
    void fullConstructor_shouldSetAllFields() {
        TransactionRequest req = new TransactionRequest(
                TransactionRequest.Type.SETTLEMENT,
                "from", "to", new BigDecimal("100"), "USDT", "memo", "req-1");

        assertEquals(TransactionRequest.Type.SETTLEMENT, req.getType());
        assertEquals("from", req.getFromAddress());
        assertEquals("to", req.getToAddress());
        assertEquals(new BigDecimal("100"), req.getAmount());
        assertEquals("USDT", req.getAsset());
        assertEquals("memo", req.getMemo());
        assertEquals("req-1", req.getRequestId());
    }

    @Test
    void fullConstructor_nullAsset_shouldDefaultToNEX() {
        TransactionRequest req = new TransactionRequest(
                TransactionRequest.Type.REFUND, "f", "t", BigDecimal.ONE, null, null, null);

        assertEquals("NEX", req.getAsset());
    }

    @Test
    void setters_shouldRoundTrip() {
        TransactionRequest req = new TransactionRequest();
        req.setType(TransactionRequest.Type.WITHDRAWAL);
        req.setFromAddress("f");
        req.setToAddress("t");
        req.setAmount(BigDecimal.TEN);
        req.setAsset("USDT");
        req.setMemo("m");
        req.setRequestId("r");

        assertEquals(TransactionRequest.Type.WITHDRAWAL, req.getType());
        assertEquals("f", req.getFromAddress());
        assertEquals("t", req.getToAddress());
        assertEquals(BigDecimal.TEN, req.getAmount());
        assertEquals("USDT", req.getAsset());
        assertEquals("m", req.getMemo());
        assertEquals("r", req.getRequestId());
    }

    @Test
    void equals_sameFields_shouldBeEqual() {
        TransactionRequest a = new TransactionRequest(
                TransactionRequest.Type.SWEEP, "f", "t", BigDecimal.ONE, "X", "m", "r");
        TransactionRequest b = new TransactionRequest(
                TransactionRequest.Type.SWEEP, "f", "t", BigDecimal.ONE, "X", "m", "r");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentFields_shouldNotBeEqual() {
        TransactionRequest a = new TransactionRequest(
                TransactionRequest.Type.SWEEP, "f", "t", BigDecimal.ONE, "X", "m", "r");
        TransactionRequest b = new TransactionRequest(
                TransactionRequest.Type.SWEEP, "f2", "t", BigDecimal.ONE, "X", "m", "r");

        assertFalse(a.equals(b));
    }

    @Test
    void equals_nullAndOtherType_shouldReturnFalse() {
        TransactionRequest a = new TransactionRequest();
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
        assertTrue(a.equals(a));
    }

    @Test
    void toString_shouldContainType() {
        TransactionRequest req = new TransactionRequest(
                TransactionRequest.Type.SETTLEMENT, "f", "t", BigDecimal.ONE, "X", "m", "r");
        String s = req.toString();
        assertNotNull(s);
        assertTrue(s.contains("SETTLEMENT"));
        assertTrue(s.contains("fromAddress='f'"));
    }

    @Test
    void typeEnum_shouldContainAllVariants() {
        assertEquals(4, TransactionRequest.Type.values().length);
        assertEquals(TransactionRequest.Type.SETTLEMENT,
                TransactionRequest.Type.valueOf("SETTLEMENT"));
        assertEquals(TransactionRequest.Type.REFUND,
                TransactionRequest.Type.valueOf("REFUND"));
        assertEquals(TransactionRequest.Type.WITHDRAWAL,
                TransactionRequest.Type.valueOf("WITHDRAWAL"));
        assertEquals(TransactionRequest.Type.SWEEP,
                TransactionRequest.Type.valueOf("SWEEP"));
    }
}