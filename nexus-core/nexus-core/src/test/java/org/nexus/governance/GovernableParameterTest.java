package org.nexus.governance;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GovernableParameter} 单元测试。
 */
class GovernableParameterTest {

    @Test
    void constructorSetsAllFields() {
        GovernableParameter p = new GovernableParameter(
                "fee", ParameterType.DECIMAL,
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("0.5"),
                EffectivePolicy.IMMEDIATE, ParameterSensitivity.LOW);
        assertEquals("fee", p.getName());
        assertEquals(ParameterType.DECIMAL, p.getType());
        assertEquals(BigDecimal.ZERO, p.getMinValue());
        assertEquals(BigDecimal.ONE, p.getMaxValue());
        assertEquals(new BigDecimal("0.5"), p.getDefaultValue());
        assertEquals(new BigDecimal("0.5"), p.getCurrentValue()); // 初始 = default
        assertEquals(EffectivePolicy.IMMEDIATE, p.getEffectivePolicy());
        assertEquals(ParameterSensitivity.LOW, p.getSensitivity());
    }

    @Test
    void toStringContainsAllFields() {
        GovernableParameter p = new GovernableParameter(
                "blockSize", ParameterType.INT,
                new BigDecimal("100"), new BigDecimal("10000"), new BigDecimal("1000"),
                EffectivePolicy.NEXT_BLOCK, ParameterSensitivity.HIGH);
        String s = p.toString();
        assertTrue(s.contains("GovernableParameter"));
        assertTrue(s.contains("blockSize"));
        assertTrue(s.contains("INT"));
        assertTrue(s.contains("NEXT_BLOCK"));
        assertTrue(s.contains("HIGH"));
    }

    @Test
    void currentValueInitializedToDefault() {
        BigDecimal def = new BigDecimal("42");
        GovernableParameter p = new GovernableParameter(
                "x", ParameterType.INT, BigDecimal.ZERO, BigDecimal.TEN, def,
                EffectivePolicy.IMMEDIATE, ParameterSensitivity.LOW);
        assertEquals(def, p.getCurrentValue());
    }
}