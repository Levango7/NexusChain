package org.nexus.settlement.risk;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RiskTransaction} 单元测试。
 * <p>覆盖默认值、getter/setter 与 toString。</p>
 */
class RiskTransactionTest {

    @Test
    void defaults_shouldBeInitialized() {
        RiskTransaction tx = new RiskTransaction();
        assertEquals("PAYMENT", tx.getType());
        assertNotNull(tx.getTimestamp());
        assertNull(tx.getMerchantId());
        assertNull(tx.getPayerAddress());
        assertNull(tx.getPayeeAddress());
        assertNull(tx.getAmount());
        assertNull(tx.getCurrency());
        assertNull(tx.getIdempotencyKey());
    }

    @Test
    void settersGetters_shouldRoundTrip() {
        Instant ts = Instant.now();
        RiskTransaction tx = new RiskTransaction();
        tx.setType("REFUND");
        tx.setMerchantId(42L);
        tx.setPayerAddress("0xPAYER");
        tx.setPayeeAddress("0xPAYEE");
        tx.setAmount(new BigDecimal("123.45"));
        tx.setCurrency("USDT");
        tx.setIdempotencyKey("idem-1");
        tx.setTimestamp(ts);

        assertEquals("REFUND", tx.getType());
        assertEquals(42L, tx.getMerchantId());
        assertEquals("0xPAYER", tx.getPayerAddress());
        assertEquals("0xPAYEE", tx.getPayeeAddress());
        assertEquals(new BigDecimal("123.45"), tx.getAmount());
        assertEquals("USDT", tx.getCurrency());
        assertEquals("idem-1", tx.getIdempotencyKey());
        assertEquals(ts, tx.getTimestamp());
    }

    @Test
    void toString_shouldContainKeyFields() {
        RiskTransaction tx = new RiskTransaction();
        tx.setMerchantId(7L);
        tx.setAmount(BigDecimal.TEN);
        tx.setCurrency("NEX");

        String s = tx.toString();
        assertNotNull(s);
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("merchantId=7"));
        org.junit.jupiter.api.Assertions.assertTrue(s.contains("currency='NEX'"));
    }
}