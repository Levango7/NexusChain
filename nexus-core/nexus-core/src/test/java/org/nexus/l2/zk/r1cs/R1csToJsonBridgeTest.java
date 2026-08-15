package org.nexus.l2.zk.r1cs;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R1csToJsonBridge 单测：Java R1CS → 桥接 JSON（供 Rust arkworks 动态电路）。
 */
class R1csToJsonBridgeTest {

    /** 构造 x^3 + x + 5 = 35 电路（witness=[1,35,3,9,27]） */
    private R1csConstraintSystem cubeCircuit() {
        Map<Integer, Long> a1 = new LinkedHashMap<>(); a1.put(2, 1L);
        Map<Integer, Long> b1 = new LinkedHashMap<>(); b1.put(2, 1L);
        Map<Integer, Long> c1 = new LinkedHashMap<>(); c1.put(3, 1L);

        Map<Integer, Long> a2 = new LinkedHashMap<>(); a2.put(3, 1L);
        Map<Integer, Long> b2 = new LinkedHashMap<>(); b2.put(2, 1L);
        Map<Integer, Long> c2 = new LinkedHashMap<>(); c2.put(4, 1L);

        Map<Integer, Long> a3 = new LinkedHashMap<>(); a3.put(4, 1L); a3.put(2, 1L); a3.put(0, 5L);
        Map<Integer, Long> b3 = new LinkedHashMap<>(); b3.put(0, 1L);
        Map<Integer, Long> c3 = new LinkedHashMap<>(); c3.put(1, 1L);

        java.util.List<R1csConstraint> cons = java.util.List.of(
                new R1csConstraint(a1, b1, c1),
                new R1csConstraint(a2, b2, c2),
                new R1csConstraint(a3, b3, c3));
        return new R1csConstraintSystem(1, 3, cons);
    }

    @Test
    void toJson_containsCircuitStructure() {
        R1csConstraintSystem sys = cubeCircuit();
        String json = R1csToJsonBridge.toJson(sys, new long[]{1, 35, 3, 9, 27});

        assertTrue(json.contains("\"num_public\":1"), "应含 num_public");
        assertTrue(json.contains("\"num_private\":3"), "应含 num_private");
        assertTrue(json.contains("[1,35,3,9,27]"), "应含 witness");
        assertTrue(json.contains("\"constraints\""), "应含 constraints");
        assertTrue(json.contains("\"a\":{\"2\":1}"), "应含约束系数 a");
        assertTrue(json.contains("\"0\":5"), "应含常量系数 5");
        // 3 个约束
        int count = json.split("\"a\":").length - 1;
        assertEquals(3, count, "应含 3 个约束");
    }

    @Test
    void toJson_witnessSizeMismatchThrows() {
        R1csConstraintSystem sys = cubeCircuit();
        assertThrows(IllegalArgumentException.class,
                () -> R1csToJsonBridge.toJson(sys, new long[]{1, 35}),
                "witness 长度不匹配应抛异常");
    }

    @Test
    void verifyRequestBody_containsPublicInputs() {
        R1csConstraintSystem sys = cubeCircuit();
        String body = R1csToJsonBridge.verifyRequestBody(sys, new long[]{1, 35, 3, 9, 27}, new long[]{35});
        assertTrue(body.contains("\"public_inputs\":[35]"), "应含公共输入");
        assertTrue(body.contains("\"witness\":"), "应含 witness");
    }
}
