package org.nexus.l2.zk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nexus.l2.zk.r1cs.R1csConstraintSystem;
import org.nexus.l2.zk.r1cs.R1csToJsonBridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZK 生产电路接入端到端测试：Rollup 状态转换电路（真实约束系统）
 * → 桥接 JSON → Rust zk-groth16-service 真实 BN254 配对验证。
 *
 * <p>前置：zk-groth16-service 运行在 50062。服务不可用时跳过（assumeTrue）。</p>
 */
class RollupCircuitRemoteVerifyTest {

    private static final String VERIFY_URL = "http://localhost:50062/v1/verify";

    @BeforeAll
    static void checkService() {
        boolean up;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:50062/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            up = client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            up = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(up,
                "zk-groth16-service 未运行，跳过生产电路远程验证");
    }

    private static String remoteVerify(String body) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        // Rust /v1/verify: circuit_json 接收 JSON 对象（非字符串）——直接嵌套
        String payload = "{\"circuit_json\":" + body + "}";
        HttpRequest req = HttpRequest.newBuilder(URI.create(VERIFY_URL))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void rollupCircuit_correctWitness_passesRealVerification() throws Exception {
        // 构造 Rollup 状态转换电路（maxBatchSize=2）：真实约束系统（C1-C5）
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(2);
        R1csConstraintSystem r1cs = circuit.buildR1cs();
        assertTrue(r1cs.getConstraintCount() >= 3, "应有真实约束");

        // witness：pre=100, post=100+txEffects, batchHash=7, txEffects=[10, 20]
        long[] witness = circuit.buildWitness(100L, 130L, 7L, new long[]{10L, 20L});
        assertEquals(r1cs.getWitnessSize(), witness.length, "witness 长度应匹配");

        // 桥接 JSON → Rust 真实验证
        String body = R1csToJsonBridge.verifyRequestBody(r1cs, witness, new long[]{100L, 130L, 7L});
        String resp = remoteVerify(body);
        assertTrue(resp.contains("\"valid\":true"),
                "正确 witness 应通过真实 BN254 配对验证: " + resp);
    }

    @Test
    void rollupCircuit_wrongWitness_rejected() throws Exception {
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(2);
        R1csConstraintSystem r1cs = circuit.buildR1cs();
        long[] witness = circuit.buildWitness(100L, 130L, 7L, new long[]{10L, 20L});
        // 篡改 witness：preStateRoot 改为 99（违反状态守恒 C1）
        long[] tampered = witness.clone();
        tampered[1] = 99L;
        String body = R1csToJsonBridge.verifyRequestBody(r1cs, tampered, new long[]{100L, 130L, 7L});
        String resp = remoteVerify(body);
        assertTrue(resp.contains("\"valid\":false"),
                "篡改 witness 应被真实验证拒绝: " + resp);
    }

    @Test
    void rollupCircuit_batchDataHashUnconstrained() throws Exception {
        // ZK 语义说明：batchDataHash（index 3）不参与 C1-C5 任何约束（未约束公共输入）
        // ——Groth16 不保护未约束公共输入（需在电路外验证/承诺），篡改它证明仍有效。
        RollupStateTransitionCircuit circuit = new RollupStateTransitionCircuit(2);
        R1csConstraintSystem r1cs = circuit.buildR1cs();
        long[] witness = circuit.buildWitness(100L, 130L, 7L, new long[]{10L, 20L});
        long[] tampered = witness.clone();
        tampered[3] = 8L;  // batchDataHash 7 → 8（未约束）
        String body = R1csToJsonBridge.verifyRequestBody(r1cs, tampered, new long[]{100L, 130L, 8L});
        String resp = remoteVerify(body);
        // 未约束公共输入篡改 → 证明仍有效（符合 Groth16 语义：公共输入须在电路外承诺）
        assertTrue(resp.contains("\"valid\":true"),
                "未约束公共输入篡改证明仍有效（ZK 语义，需电路外承诺）: " + resp);
    }
}
