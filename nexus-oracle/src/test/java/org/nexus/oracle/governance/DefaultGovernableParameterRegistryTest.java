package org.nexus.oracle.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultGovernableParameterRegistry} 单元测试。
 */
class DefaultGovernableParameterRegistryTest {

    private DefaultGovernableParameterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultGovernableParameterRegistry();
    }

    @Test
    void validate_validNameAndValue_shouldPass() {
        assertTrue(registry.validate("name", "value"));
    }

    @Test
    void validate_blankName_shouldFail() {
        assertFalse(registry.validate("", "value"));
        assertFalse(registry.validate("   ", "value"));
    }

    @Test
    void validate_nullName_shouldFail() {
        assertFalse(registry.validate(null, "value"));
    }

    @Test
    void validate_nullValue_shouldFail() {
        assertFalse(registry.validate("name", null));
    }

    @Test
    void setParameter_valid_shouldApply() {
        assertTrue(registry.setParameter("foo", "bar"));
        assertEquals("bar", registry.getParameter("foo"));
    }

    @Test
    void setParameter_invalid_shouldNotApply() {
        assertFalse(registry.setParameter(null, "v"));
        assertFalse(registry.setParameter("name", null));
        assertNull(registry.getParameter("name"));
    }

    @Test
    void getParameter_nullName_shouldReturnNull() {
        assertNull(registry.getParameter(null));
    }

    @Test
    void getParameter_unknown_shouldReturnNull() {
        assertNull(registry.getParameter("nope"));
    }

    @Test
    void snapshot_shouldReturnCopy() {
        registry.setParameter("a", "1");
        registry.setParameter("b", "2");

        Map<String, Object> snap = registry.snapshot();
        assertEquals(2, snap.size());
        assertEquals("1", snap.get("a"));

        // 修改快照不影响内部
        snap.put("a", "X");
        assertEquals("1", registry.getParameter("a"));
    }

    @Test
    void snapshot_empty_shouldBeEmpty() {
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test
    void restore_fromSnapshot_shouldReplaceState() {
        registry.setParameter("a", "1");
        registry.setParameter("b", "2");

        Map<String, Object> snap = new HashMap<>();
        snap.put("x", "10");

        registry.restore(snap);
        assertNull(registry.getParameter("a"));
        assertEquals("10", registry.getParameter("x"));
    }

    @Test
    void restore_nullSnapshot_shouldClearAll() {
        registry.setParameter("a", "1");
        registry.restore(null);
        assertNull(registry.getParameter("a"));
    }
}