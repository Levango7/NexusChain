package org.nexus.l2.zk.r1cs;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R1CS → JSON 桥接（ZK 方案 C 正式电路桥接：Java 约束系统 → Rust arkworks）。
 *
 * <p>把 {@link R1csConstraintSystem} + witness 序列化为 Rust 侧可解析的 JSON：</p>
 * <pre>
 * {
 *   "num_public": 2, "num_private": 3,
 *   "witness": [1, 35, 3, 9, 27],          // [1, public..., private...]
 *   "constraints": [
 *     {"a": {"1": 1}, "b": {"1": 1}, "c": {"2": 1}},  // x * x = x2
 *     ...
 *   ]
 * }
 * </pre>
 *
 * <p>Rust 侧（zk-groth16-service bridge 模块）解析后动态构建 arkworks 电路，
 * 用 witness 填充变量，执行真实 Groth16 prove/verify。</p>
 */
public final class R1csToJsonBridge {

    private R1csToJsonBridge() {}

    /**
     * 序列化约束系统 + witness 为桥接 JSON（long 兼容重载，委托 BigInteger 版本）。
     *
     * @param system  约束系统
     * @param witness 完整 witness（w[0]=1，随后 public，再 private）
     * @return JSON 字符串（无依赖的紧凑实现）
     * @deprecated 使用 {@link #toJson(R1csConstraintSystem, BigInteger[])}
     */
    @Deprecated
    public static String toJson(R1csConstraintSystem system, long[] witness) {
        if (witness == null) {
            return toJson(system, (BigInteger[]) null);
        }
        BigInteger[] bigWitness = new BigInteger[witness.length];
        for (int i = 0; i < witness.length; i++) {
            bigWitness[i] = BigInteger.valueOf(witness[i]);
        }
        return toJson(system, bigWitness);
    }

    /**
     * 序列化约束系统 + witness 为桥接 JSON（A1-R3：BigInteger witness，支持 256 位状态根）。
     *
     * @param system  约束系统
     * @param witness 完整 witness（w[0]=1，随后 public，再 private）
     * @return JSON 字符串（无依赖的紧凑实现）
     */
    public static String toJson(R1csConstraintSystem system, BigInteger[] witness) {
        if (system == null || witness == null) {
            throw new IllegalArgumentException("system and witness are required");
        }
        if (witness.length != system.getWitnessSize()) {
            throw new IllegalArgumentException("witness size mismatch: expected "
                    + system.getWitnessSize() + ", got " + witness.length);
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"num_public\":").append(system.getNumPublic());
        sb.append(",\"num_private\":").append(system.getNumPrivate());
        sb.append(",\"witness\":[");
        for (int i = 0; i < witness.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(witness[i]);
        }
        sb.append("],\"constraints\":[");
        java.util.List<R1csConstraint> cons = system.getConstraints();
        for (int i = 0; i < cons.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(toConstraintJson(cons.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String toConstraintJson(R1csConstraint c) {
        return "{\"a\":" + toCoeffJson(c.getA())
                + ",\"b\":" + toCoeffJson(c.getB())
                + ",\"c\":" + toCoeffJson(c.getC()) + "}";
    }

    private static String toCoeffJson(Map<Integer, BigInteger> coeffs) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        // 按索引排序保证确定性
        java.util.List<Integer> keys = new java.util.ArrayList<>(coeffs.keySet());
        java.util.Collections.sort(keys);
        for (Integer k : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(k).append("\":").append(coeffs.get(k));
        }
        return sb.append('}').toString();
    }

    /**
     * 桥接 JSON 请求体（给 /v1/verify 的完整载荷：电路 + witness + 公共输入）。
     *
     * @deprecated 使用 {@link #verifyRequestBody(R1csConstraintSystem, BigInteger[], BigInteger[])}
     */
    @Deprecated
    public static String verifyRequestBody(R1csConstraintSystem system, long[] witness, long[] publicInputs) {
        if (witness == null) {
            return verifyRequestBody(system, (BigInteger[]) null, (BigInteger[]) null);
        }
        BigInteger[] bigWitness = new BigInteger[witness.length];
        for (int i = 0; i < witness.length; i++) {
            bigWitness[i] = BigInteger.valueOf(witness[i]);
        }
        BigInteger[] bigInputs = null;
        if (publicInputs != null) {
            bigInputs = new BigInteger[publicInputs.length];
            for (int i = 0; i < publicInputs.length; i++) {
                bigInputs[i] = BigInteger.valueOf(publicInputs[i]);
            }
        }
        return verifyRequestBody(system, bigWitness, bigInputs);
    }

    /**
     * 桥接 JSON 请求体（给 /v1/verify 的完整载荷：电路 + witness + 公共输入，A1-R3：BigInteger）。
     */
    public static String verifyRequestBody(R1csConstraintSystem system, BigInteger[] witness, BigInteger[] publicInputs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("num_public", system.getNumPublic());
        body.put("num_private", system.getNumPrivate());
        body.put("witness", witness);
        body.put("constraints", serializeConstraints(system.getConstraints()));
        if (publicInputs != null) {
            body.put("public_inputs", publicInputs);
        }
        return toJsonBody(body);
    }

    private static java.util.List<Map<String, Object>> serializeConstraints(java.util.List<R1csConstraint> cons) {
        java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (R1csConstraint c : cons) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", c.getA());
            m.put("b", c.getB());
            m.put("c", c.getC());
            out.add(m);
        }
        return out;
    }

    private static String toJsonBody(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(toJsonValue(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String toJsonValue(Object v) {
        if (v instanceof Number) return v.toString();
        if (v instanceof BigInteger[]) {
            BigInteger[] arr = (BigInteger[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i] == null ? "null" : arr[i].toString());
            }
            return sb.append(']').toString();
        }
        if (v instanceof long[]) {
            long[] arr = (long[]) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(arr[i]);
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<Object, Object> m = (Map<Object, Object>) v;
            return toJsonBody2(m);
        }
        if (v instanceof java.util.List) {
            @SuppressWarnings("unchecked")
            java.util.List<Object> l = (java.util.List<Object>) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(toJsonValue(l.get(i)));
            }
            return sb.append(']').toString();
        }
        return "\"" + v + "\"";
    }

    private static String toJsonBody2(Map<Object, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Object, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(toJsonValue(e.getValue()));
        }
        return sb.append('}').toString();
    }
}
