package org.nexus.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ParameterChange} 单元测试。
 */
class ParameterChangeTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        ParameterChange c = new ParameterChange();
        assertNull(c.getParameterName());
        assertNull(c.getOldValue());
        assertNull(c.getNewValue());
        assertEquals(0, c.getEffectiveHeight());
    }

    @Test
    void fullConstructorSetsFields() {
        ParameterChange c = new ParameterChange("fee", "0.1", "0.2", 1000);
        assertEquals("fee", c.getParameterName());
        assertEquals("0.1", c.getOldValue());
        assertEquals("0.2", c.getNewValue());
        assertEquals(1000, c.getEffectiveHeight());
    }

    @Test
    void settersUpdateFields() {
        ParameterChange c = new ParameterChange();
        c.setParameterName("blockSize");
        c.setOldValue("100");
        c.setNewValue("200");
        c.setEffectiveHeight(500);
        assertEquals("blockSize", c.getParameterName());
        assertEquals("100", c.getOldValue());
        assertEquals("200", c.getNewValue());
        assertEquals(500, c.getEffectiveHeight());
    }

    @Test
    void negativeEffectiveHeightAllowed() {
        ParameterChange c = new ParameterChange("x", "1", "2", -1);
        assertEquals(-1, c.getEffectiveHeight());
    }
}