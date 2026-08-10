package org.nexus.oracle.price;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PriceEntry} Lombok 实体单元测试。
 */
class PriceEntryEntityTest {

    @Test
    void builder_shouldSetAllFields() {
        Instant now = Instant.now();
        PriceEntry e = PriceEntry.builder()
                .asset("BTC")
                .price(new BigDecimal("50000"))
                .timestamp(now)
                .source("BINANCE")
                .confidence(0.95)
                .build();

        assertEquals("BTC", e.getAsset());
        assertEquals(new BigDecimal("50000"), e.getPrice());
        assertEquals(now, e.getTimestamp());
        assertEquals("BINANCE", e.getSource());
        assertEquals(0.95, e.getConfidence(), 0.001);
    }

    @Test
    void noArgsConstructor_shouldHaveNulls() {
        PriceEntry e = new PriceEntry();
        assertNull(e.getAsset());
        assertNull(e.getPrice());
        assertNull(e.getTimestamp());
        assertNull(e.getSource());
        assertNull(e.getConfidence());
    }

    @Test
    void setters_shouldRoundTrip() {
        PriceEntry e = new PriceEntry();
        e.setAsset("ETH");
        e.setPrice(BigDecimal.TEN);
        e.setSource("CHAINLINK");
        e.setConfidence(0.5);

        assertEquals("ETH", e.getAsset());
        assertEquals(BigDecimal.TEN, e.getPrice());
        assertEquals("CHAINLINK", e.getSource());
        assertEquals(0.5, e.getConfidence(), 0.001);
    }

    @Test
    void equalsAndHashCode_shouldWork() {
        PriceEntry a = PriceEntry.builder().asset("BTC").price(new BigDecimal("100")).build();
        PriceEntry b = PriceEntry.builder().asset("BTC").price(new BigDecimal("100")).build();
        PriceEntry c = PriceEntry.builder().asset("ETH").price(new BigDecimal("100")).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
        assertTrue(a.equals(a));
    }

    @Test
    void canEqual_shouldDistinguishTypes() {
        PriceEntry e = new PriceEntry();
        assertFalse(e.canEqual("string"));
        assertTrue(e.canEqual(new PriceEntry()));
    }

    @Test
    void toString_shouldContainFields() {
        PriceEntry e = PriceEntry.builder().asset("BTC").price(new BigDecimal("50000")).build();
        String s = e.toString();
        assertNotNull(s);
        assertTrue(s.contains("BTC"));
        assertTrue(s.contains("50000"));
    }
}