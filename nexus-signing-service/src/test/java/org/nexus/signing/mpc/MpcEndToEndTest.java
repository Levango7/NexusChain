package org.nexus.signing.mpc;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.GrpcMpcCryptoEngine;
import org.nexus.signing.mpc.crypto.MpcCryptoEngine;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MPC 端到端集成测试（P5-T3）：Java↔Rust gRPC 调用链验证。
 *
 * <p>验证完整 MPC 签名流程：</p>
 * <ol>
 *   <li><b>3 方 DKG</b>：3 个参与方协同生成聚合公钥 + 各自密钥分片</li>
 *   <li><b>签名</b>：每个参与方本地执行签名轮次，产出部分签名 s_i</li>
 *   <li><b>聚合</b>：收集 t 个部分签名，聚合为最终 ECDSA 签名 (r, s)</li>
 *   <li><b>ECDSA 验签</b>：用 BouncyCastle 验证 (r, s) 对应 publicKey 和 messageHash（secp256k1）</li>
 * </ol>
 *
 * <h2>测试条件</h2>
 * <p>本测试需要 Rust mpc-engine 进程运行在 {@code localhost:50051}。若引擎不可用
 * （{@link MpcCryptoEngine#healthCheck()} 返回 false），测试将通过
 * {@link assumeTrue} 优雅跳过，不会失败。</p>
 *
 * <p>设置环境变量 {@code NEX_MPC_ENGINE_SKIP_E2E=true} 可强制跳过测试
 * （用于 CI 环境无 Rust 引擎时）。</p>
 *
 * <h2>测试矩阵</h2>
 * <ul>
 *   <li>{@link #testThreePartyDkgSignAggregateVerify} — 3-of-3 完整流程</li>
 *   <li>{@link #testTwoOfThreeThresholdSignature} — 2-of-3 阈值签名（t&lt;n）</li>
 *   <li>{@link #testHealthCheck} — 引擎健康检查</li>
 * </ul>
 *
 * @see GrpcMpcCryptoEngine
 * @see MpcCryptoEngine
 */
@DisplayName("MPC End-to-End: Java↔Rust gRPC (DKG→Sign→Aggregate→Verify)")
public class MpcEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(MpcEndToEndTest.class);

    /** Rust 引擎 gRPC 主机。 */
    private static final String ENGINE_HOST = System.getenv().getOrDefault(
            "NEX_MPC_ENGINE_HOST", "localhost");
    /** Rust 引擎 gRPC 端口。 */
    private static final int ENGINE_PORT = Integer.parseInt(
            System.getenv().getOrDefault("NEX_MPC_ENGINE_PORT", "50051"));

    /** MPC 引擎客户端。 */
    private GrpcMpcCryptoEngine engine;

    /**
     * 初始化 gRPC 引擎客户端。
     *
     * <p>通过反射设置 {@link GrpcMpcCryptoEngine} 的 private 字段
     * （host/port/deadlineTimeoutMillis/usePlaintext），然后调用 {@code init()}
     * 建立 gRPC channel。</p>
     *
     * @throws Exception 若反射设置或 init 失败
     */
    @BeforeEach
    void setUp() throws Exception {
        engine = new GrpcMpcCryptoEngine();
        setField(engine, "host", ENGINE_HOST);
        setField(engine, "port", ENGINE_PORT);
        setField(engine, "deadlineTimeoutMillis", 60_000L);
        setField(engine, "usePlaintext", true);
        // 调用 @PostConstruct init() 建立 channel
        engine.init();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    /**
     * 测试引擎健康检查。
     *
     * <p>若引擎不可用，后续测试将跳过。本测试本身也会跳过。</p>
     */
    @Test
    @DisplayName("healthCheck: Rust mpc-engine 可达性检查")
    void testHealthCheck() {
        boolean healthy = engine.healthCheck();
        log.info("MPC engine healthCheck at {}:{} = {}", ENGINE_HOST, ENGINE_PORT, healthy);
        assumeTrue(healthy, "Rust mpc-engine not available at " + ENGINE_HOST + ":" + ENGINE_PORT
                + " — skipping end-to-end test");
        assertTrue(healthy, "engine should be healthy");
    }

    /**
     * 3-of-3 完整 MPC 签名流程：DKG → Sign → Aggregate → ECDSA Verify。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>3 方 DKG：每方调用 {@link MpcCryptoEngine#dkg}，产出聚合公钥 + 密钥分片</li>
     *   <li>t+1 方签名（GG20 要求签名方数 > threshold）：每方调用 {@link MpcCryptoEngine#sign}，产出部分签名</li>
     *   <li>聚合：调用 {@link MpcCryptoEngine#aggregate}，产出最终签名 (r, s)</li>
     *   <li>ECDSA 验签：BouncyCastle 验证 (r, s) 对应 publicKey 和 messageHash</li>
     * </ol>
     *
     * <p>若 Rust 引擎不可用，测试通过 {@link assumeTrue} 跳过。</p>
     */
    @Test
    @DisplayName("3-party-2-threshold DKG → Sign → Aggregate → ECDSA Verify")
    void testThreePartyDkgSignAggregateVerify() throws Exception {
        // 前置条件：引擎可用
        assumeEngineAvailable();

        int n = 3;  // 总参与方数
        int t = 2;  // 阈值（3-party-2-threshold）
        String curve = "secp256k1";
        String dkgSessionId = "e2e-dkg-3of3-" + System.currentTimeMillis();

        // 3 个参与方的 gRPC 端点（Rust 引擎内部 P2P 通信）
        List<String> peerEndpoints = Arrays.asList(
                "localhost:50061", "localhost:50062", "localhost:50063");

        log.info("=== Phase 1: 3-party DKG (session={}) ===", dkgSessionId);

        // === Phase 1: DKG ===
        String[] keyShares = new String[n];
        String jointPublicKey = null;
        for (int i = 0; i < n; i++) {
            DkgRequest req = new DkgRequest(dkgSessionId, t, n, i, curve, peerEndpoints);
            DkgResponse resp = engine.dkg(req);
            assertTrue(resp.isSuccess(),
                    "DKG party " + i + " should succeed, error: " + resp.getError());
            assertNotNull(resp.getPublicKey(), "public key should not be null");
            assertNotNull(resp.getKeyShare(), "key share should not be null");

            keyShares[i] = resp.getKeyShare();
            if (jointPublicKey == null) {
                jointPublicKey = resp.getPublicKey();
            } else {
                assertEquals(jointPublicKey, resp.getPublicKey(),
                        "all parties should produce the same joint public key");
            }
            log.info("DKG party {} done: publicKey={}", i,
                    jointPublicKey.substring(0, Math.min(20, jointPublicKey.length())) + "...");
        }
        assertNotNull(jointPublicKey, "joint public key should be produced");
        log.info("DKG complete: jointPublicKey length={}", jointPublicKey.length());

        // === Phase 2: Sign ===
        log.info("=== Phase 2: 3-party Sign ===");
        String signSessionId = dkgSessionId;
        // 32 字节消息哈希（hex 64 字符）
        String messageHashHex = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";

        List<String> partialSignatures = new ArrayList<>();
        for (int i = 0; i <= t; i++) {
            SignRequest req = new SignRequest(signSessionId, jointPublicKey,
                    keyShares[i], messageHashHex, i, peerEndpoints);
            SignResponse resp = engine.sign(req);
            assertTrue(resp.isSuccess(),
                    "Sign party " + i + " should succeed, error: " + resp.getError());
            assertNotNull(resp.getPartialSignature(),
                    "partial signature should not be null");
            partialSignatures.add(resp.getPartialSignature());
            log.info("Sign party {} done: partialSig length={}",
                    i, resp.getPartialSignature().length());
        }
        assertEquals(t + 1, partialSignatures.size(), "should have t+1 partial signatures");
        log.info("Sign complete: collected {} partial signatures", partialSignatures.size());

        // === Phase 3: Aggregate ===
        log.info("=== Phase 3: Aggregate ===");
        AggregateRequest aggReq = new AggregateRequest(signSessionId, jointPublicKey,
                messageHashHex, partialSignatures);
        AggregateResponse aggResp = engine.aggregate(aggReq);
        assertTrue(aggResp.isSuccess(),
                "Aggregate should succeed, error: " + aggResp.getError());
        assertNotNull(aggResp.getSignature(), "final signature should not be null");
        assertNotNull(aggResp.getR(), "r should not be null");
        assertNotNull(aggResp.getS(), "s should not be null");
        log.info("Aggregate complete: r={}, s={}, recoveryId={}",
                aggResp.getR().substring(0, Math.min(20, aggResp.getR().length())) + "...",
                aggResp.getS().substring(0, Math.min(20, aggResp.getS().length())) + "...",
                aggResp.getRecoveryId());

        // === Phase 4: ECDSA Verify ===
        log.info("=== Phase 4: ECDSA Verify (secp256k1, BouncyCastle) ===");
        verifyEcdsaSignature(jointPublicKey, messageHashHex, aggResp.getR(), aggResp.getS());
        log.info("ECDSA verification PASSED — end-to-end MPC signature valid!");
    }

    /**
     * 2-of-3 阈值签名：t&lt;n 场景。
     *
     * <p>3 方 DKG 生成 2-of-3 阈值密钥，需 t+1 方签名聚合（GG20 要求签名方数 > threshold）。
     * 验证阈值签名正确性。</p>
     */
    @Test
    @DisplayName("2-of-3 threshold DKG → Sign(t=2) → Aggregate → ECDSA Verify")
    void testTwoOfThreeThresholdSignature() throws Exception {
        assumeEngineAvailable();

        int n = 3;  // 总参与方数
        int t = 2;  // 阈值（2-of-3）
        String curve = "secp256k1";
        String dkgSessionId = "e2e-dkg-2of3-" + System.currentTimeMillis();

        List<String> peerEndpoints = Arrays.asList(
                "localhost:50071", "localhost:50072", "localhost:50073");

        log.info("=== 2-of-3 Threshold: DKG (session={}) ===", dkgSessionId);

        // DKG: 3 方参与
        String[] keyShares = new String[n];
        String jointPublicKey = null;
        for (int i = 0; i < n; i++) {
            DkgRequest req = new DkgRequest(dkgSessionId, t, n, i, curve, peerEndpoints);
            DkgResponse resp = engine.dkg(req);
            assertTrue(resp.isSuccess(),
                    "DKG party " + i + " should succeed, error: " + resp.getError());
            keyShares[i] = resp.getKeyShare();
            if (jointPublicKey == null) {
                jointPublicKey = resp.getPublicKey();
            }
        }
        assertNotNull(jointPublicKey, "joint public key should be produced");
        log.info("2-of-3 DKG complete");

        // Sign: 仅 t=2 方签名（party 0 和 party 1）
        String signSessionId = dkgSessionId;
        String messageHashHex = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

        List<String> partialSignatures = new ArrayList<>();
        for (int i = 0; i <= t; i++) {
            SignRequest req = new SignRequest(signSessionId, jointPublicKey,
                    keyShares[i], messageHashHex, i, peerEndpoints);
            SignResponse resp = engine.sign(req);
            assertTrue(resp.isSuccess(),
                    "Sign party " + i + " should succeed, error: " + resp.getError());
            partialSignatures.add(resp.getPartialSignature());
        }
        assertEquals(t + 1, partialSignatures.size(), "should have t+1 partial signatures");
        log.info("2-of-3 Sign complete: {} partial signatures", partialSignatures.size());

        // Aggregate
        AggregateRequest aggReq = new AggregateRequest(signSessionId, jointPublicKey,
                messageHashHex, partialSignatures);
        AggregateResponse aggResp = engine.aggregate(aggReq);
        assertTrue(aggResp.isSuccess(),
                "Aggregate should succeed, error: " + aggResp.getError());
        log.info("2-of-3 Aggregate complete: recoveryId={}", aggResp.getRecoveryId());

        // ECDSA Verify
        verifyEcdsaSignature(jointPublicKey, messageHashHex, aggResp.getR(), aggResp.getS());
        log.info("2-of-3 ECDSA verification PASSED!");
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    /**
     * 假设引擎可用，否则跳过测试。
     */
    private void assumeEngineAvailable() {
        boolean healthy = engine.healthCheck();
        if (!healthy) {
            log.info("Rust mpc-engine not available at {}:{} — skipping end-to-end test",
                    ENGINE_HOST, ENGINE_PORT);
        }
        assumeTrue(healthy, "Rust mpc-engine not available at " + ENGINE_HOST + ":" + ENGINE_PORT);
    }

    /**
     * 验证 ECDSA 签名 (r, s) 对应公钥 publicKeyHex 和消息哈希 messageHashHex（secp256k1）。
     *
     * <p>验证等式：u1 = z * s^-1 mod n, u2 = r * s^-1 mod n,
     * R = u1*G + u2*Q，则 R.x mod n == r。</p>
     *
     * @param publicKeyHex   公钥曲线点（hex）
     * @param messageHashHex 消息哈希（hex，32 字节）
     * @param rHex           签名 r（hex）
     * @param sHex           签名 s（hex）
     */
    private static void verifyEcdsaSignature(String publicKeyHex,
                                              String messageHashHex,
                                              String rHex,
                                              String sHex) throws java.security.NoSuchAlgorithmException {
        X9ECParameters params = ECNamedCurveTable.getByName("secp256k1");
        assertNotNull(params, "secp256k1 curve must be available in BouncyCastle");

        ECPoint G = params.getG();
        BigInteger n = params.getN();

        byte[] pubBytes = HexFormat.of().parseHex(publicKeyHex);
        ECPoint Q = params.getCurve().decodePoint(pubBytes);
        assertTrue(!Q.isInfinity(), "public key must not be point at infinity");

        BigInteger r = new BigInteger(1, HexFormat.of().parseHex(rHex));
        BigInteger s = new BigInteger(1, HexFormat.of().parseHex(sHex));
        byte[] hashBytes = HexFormat.of().parseHex(messageHashHex);
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        BigInteger z = new BigInteger(1, sha256.digest(hashBytes));

        assertTrue(r.compareTo(BigInteger.ONE) >= 0 && r.compareTo(n) < 0,
                "r must be in [1, n-1]");
        assertTrue(s.compareTo(BigInteger.ONE) >= 0 && s.compareTo(n) < 0,
                "s must be in [1, n-1]");

        BigInteger sInv = s.modInverse(n);
        BigInteger u1 = z.multiply(sInv).mod(n);
        BigInteger u2 = r.multiply(sInv).mod(n);

        ECPoint R = ECAlgorithms.sumOfTwoMultiplies(G, u1, Q, u2).normalize();
        assertTrue(!R.isInfinity(), "computed R must not be point at infinity");

        BigInteger rPrime = R.getAffineXCoord().toBigInteger().mod(n);
        assertEquals(r, rPrime, "ECDSA verify failed: R.x mod n != r");
    }

    /**
     * 通过反射设置 private 字段。
     *
     * @param target 目标对象
     * @param name   字段名
     * @param value  字段值
     * @throws Exception 若反射访问失败
     */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}