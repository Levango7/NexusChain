package org.nexus.governance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConfigSnapshot} 单元测试。
 */
class ConfigSnapshotTest {

    @Test
    void gettersReturnValues() {
        Instant now = Instant.now();
        Map<String, BigDecimal> values = new HashMap<>();
        values.put("fee", new BigDecimal("0.5"));
        ConfigSnapshot s = new ConfigSnapshot(3, now, "pre-upgrade", values);
        assertEquals(3, s.getVersion());
        assertEquals(now, s.getTimestamp());
        assertEquals("pre-upgrade", s.getTag());
        assertEquals(new BigDecimal("0.5"), s.getValues().get("fee"));
    }

    @Test
    void nullValuesBecomesEmptyMap() {
        ConfigSnapshot s = new ConfigSnapshot(0, Instant.now(), "tag", null);
        assertNotNull(s.getValues());
        assertTrue(s.getValues().isEmpty());
    }

    @Test
    void valuesMapIsUnmodifiable() {
        Map<String, BigDecimal> values = new HashMap<>();
        values.put("k", BigDecimal.ONE);
        ConfigSnapshot s = new ConfigSnapshot(1, Instant.now(), "t", values);
        assertThrows(UnsupportedOperationException.class, () ->
                s.getValues().put("new", BigDecimal.TEN));
    }

    @Test
    void toStringContainsKeyFields() {
        ConfigSnapshot s = new ConfigSnapshot(7, Instant.now(), "tag",
                Collections.singletonMap("k", BigDecimal.ONE));
        String str = s.toString();
        assertTrue(str.contains("ConfigSnapshot"));
        assertTrue(str.contains("version=7"));
        assertTrue(str.contains("tag='tag'"));
        assertTrue(str.contains("size=1"));
    }
}