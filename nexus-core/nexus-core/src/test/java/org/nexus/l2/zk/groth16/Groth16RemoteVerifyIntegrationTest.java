package org.nexus.l2.zk.groth16;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ZK 方案 C 集成测试：Java {@link Groth16ProofSystem#verifyRemote} 对接
 * Rust zk-groth16-service（真实 BN254 配对验证）。
 *
 * <p>前置：zk-groth16-service 运行在 50062（HTTP /v1/verify）。
 * 服务不可用时测试跳过（assumeTrue），不阻塞常规测试。</p>
 */
class Groth16RemoteVerifyIntegrationTest {

    private static final String REMOTE_URL = "http://localhost:50062/v1/verify";

    @BeforeAll
    static void checkService() {
        boolean up;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(2)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://localhost:50062/health"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET().build();
            var resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            up = resp.statusCode() == 200;
        } catch (Exception e) {
            up = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(up,
                "zk-groth16-service 未运行（需先起 Rust 服务），跳过远程验证测试");
    }

    @Test
    void remoteVerify_acceptsRealProof() {
        // 演示电路（x^3+x+5=35）：空公共输入 → 服务端内置电路真实验证 → valid=true
        Groth16ProofSystem sys = new Groth16ProofSystem();
        assertTrue(sys.verifyRemote(REMOTE_URL, new long[]{35}),
                "真实 BN254 配对验证应通过（输入 35）");
    }

    @Test
    void remoteVerify_rejectsWrongInput() {
        // 错误公共输入（23 ≠ 35）→ 真实配对验证失败
        Groth16ProofSystem sys = new Groth16ProofSystem();
        assertFalse(sys.verifyRemote(REMOTE_URL, new long[]{23}),
                "错误输入应验证失败（真实配对安全语义）");
    }

    @Test
    void remoteVerify_serviceDownFailsClosed() {
        // 服务不可用 → fail-closed（false，不降级到 Schnorr/mock）
        Groth16ProofSystem sys = new Groth16ProofSystem();
        assertFalse(sys.verifyRemote("http://localhost:59999/v1/verify", new long[]{35}),
                "服务不可用必须 fail-closed");
    }
}
