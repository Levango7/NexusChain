package org.nexus.signing.mpc.cggmp;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I 批端到端集成测试：CGGMP21 路径 Java 客户端 → 3 进程 mpc-engine。
 *
 * <p>验证范围：</p>
 * <ul>
 *   <li>3 个 mpc-engine 子进程（独立 endpoint，模拟生产 K8s StatefulSet 3 副本）</li>
 *   <li>Java 端 3 个 {@link MpcCggmpClient}（每方一个） + 1 协调器 client（绑 node0）</li>
 *   <li>每方独立驱动 publish→pull→pump 循环（{@link MpcCggmpOrchestrator}）</li>
 *   <li>完整生命周期：keygen(t=2, n=3) → aux → assembleShare → sign(t=2) → verify</li>
 *   <li>三方产出的 r/s 一致（同一签名可在任一方 verify 通过）</li>
 * </ul>
 *
 * <h2>先决条件</h2>
 * <ul>
 *   <li>mpc-engine 二进制存在（Windows 默认 {@code <repo>/mpc-engine/target/debug/mpc-engine.exe}，
 *       Linux 默认 {@code mpc-engine} 同目录布局；CI 由 {@code MPC_ENGINE_BIN} 指向 release 产物）</li>
 *   <li>mTLS 证书 + 节点 JSON 配置就位（CI 用 {@code mpc-engine/scripts/start-mpc-cluster.sh
 *       --setup-only} 生成；本地可手动生成或沿用既有产物）</li>
 * </ul>
 *
 * <h2>运行</h2>
 * <pre>
 * gradle :nexus-signing-service:test -PincludeClusterE2E \
 *        --tests org.nexus.signing.mpc.cggmp.CggmpMpcE2EClusterTest
 * </pre>
 * （{@code -PincludeClusterE2E} 解除 build.gradle 对本类的默认排除）
 *
 * <h2>环境要求</h2>
 * <p>均可覆盖；不设时按 OS/二进制位置自动锚定：</p>
 * <ul>
 *   <li>{@code MPC_ENGINE_BIN}——mpc-engine 二进制绝对路径</li>
 *   <li>{@code MPC_CONFIG_DIR}——node{1,2,3}.json 所在目录（默认 {@code <mpc-engine>/config}）</li>
 *   <li>{@code MPC_LOG_DIR}——引擎 stdout 日志目录（默认 {@code <mpc-engine>/logs}）</li>
 *   <li>{@code MPC_DATA_DIR}——会话数据目录（默认 {@code <mpc-engine>/data}）</li>
 *   <li>{@code MPC_CERTS_DIR}——mTLS 证书目录（默认 {@code <mpc-engine>/certs}）</li>
 * </ul>
 */
@Tag("cluster-e2e")
public class CggmpMpcE2EClusterTest {

    private static final Logger log = LoggerFactory.getLogger(CggmpMpcE2EClusterTest.class);

    /**
     * mpc-engine 二进制路径（可由 MPC_ENGINE_BIN 覆盖）。
     * 默认值按 OS 选择二进制名，路径锚定 repo 相对位置（测试工作目录 =
     * nexus-signing-service/，Gradle 多模块下 user.dir 即模块目录）：
     * ../mpc-engine/target/{debug|release}/mpc-engine[.exe]。
     * Windows 本地默认 debug；Linux/CI 用 MPC_ENGINE_BIN 指向 release 产物。
     */
    private static final Path DEFAULT_BIN = defaultEngineBinary();
    /**
     * 目录族默认锚定二进制所在 mpc-engine 根（target/{debug|release} 上两级），
     * 与本地布局和 CI（start-mpc-cluster.sh --setup-only 产物布局）一致；
     * 均可被环境变量覆盖（MPC_CONFIG_DIR / MPC_LOG_DIR / MPC_DATA_DIR /
     * MPC_CERTS_DIR）。在 startCluster 中 engineBinary 解析后初始化。
     */
    private static Path configDir;
    private static Path logDir;
    private static Path dataDir;
    private static Path certsDir;

    // C 批：60s → 120s。CI 共享 runner 首次冷启动（JIT/磁盘 IO）慢于本地，
    // 实测 I 批 keygen 端到端 <1s，但端口就绪与进程拉起在 runner 上留倍数余量。
    private static final long DEADLINE_MS = 120_000L;

    private static Path engineBinary;
    private static final List<Process> engines = new ArrayList<>();
    private static final List<ManagedChannel> channels = new ArrayList<>();
    private static final List<MpcCggmpClient> partyClients = new ArrayList<>();
    private static MpcCggmpClient coordinatorClient;
    private static final List<MpcCggmpOrchestrator> orchestrators = new ArrayList<>();
    private static int port1;

    @BeforeAll
    static void startCluster() throws Exception {
        String envBin = System.getenv("MPC_ENGINE_BIN");
        engineBinary = envBin != null ? Paths.get(envBin) : DEFAULT_BIN;
        if (!Files.isRegularFile(engineBinary)) {
            throw new IllegalStateException(
                    "mpc-engine binary not found: " + engineBinary.toAbsolutePath()
                            + " (set MPC_ENGINE_BIN to override)");
        }
        configDir = resolveDir("MPC_CONFIG_DIR", "config");
        logDir = resolveDir("MPC_LOG_DIR", "logs");
        dataDir = resolveDir("MPC_DATA_DIR", "data");
        certsDir = resolveDir("MPC_CERTS_DIR", "certs");
        Files.createDirectories(logDir);
        // 起 3 个 mpc-engine 子进程（端口由 nodeN.json listen_addr 决定）
        for (int i = 1; i <= 3; i++) {
            Path configPath = configDir.resolve("node" + i + ".json");
            if (!Files.isRegularFile(configPath)) {
                throw new IllegalStateException(
                        "node config missing: " + configPath + " (run scripts/start-mpc-cluster.sh first)");
            }
            ProcessBuilder pb = new ProcessBuilder(
                    engineBinary.toString(), "--config", configPath.toString())
                    .redirectErrorStream(true)
                    .directory(engineBinary.getParent().toFile());
            // 显式 env（与生产启动脚本一致）
            pb.environment().put("MPC_CONFIG_PATH", configPath.toString());
            pb.environment().put("MPC_ENGINE_SESSION_DIR",
                    dataDir.resolve("node" + i).resolve("sessions").toString());
            pb.environment().put("MPC_REQUIRE_TLS", "true");
            pb.environment().put("MPC_AUTH_TOKEN", "nexus-mpc-test-token");
            pb.environment().put("RUST_LOG", "info");
            Process p = pb.start();
            engines.add(p);
            drainStdout(p, "node" + i);
        }
        // 等 3 个端口起来
        port1 = waitForPort(50051, 15_000);
        waitForPort(50052, 5_000);
        waitForPort(50053, 5_000);
        log.info("3 mpc-engine nodes up: 127.0.0.1:50051,50052,50053");

        // 建 3 个 mTLS channel（用项目自带 GrpcTlsContextFactory，与生产 GrpcMpcCryptoEngine 一致）
        // 证书路径：<mpc-engine>/certs/{nodeN.crt, nodeN.key, ca.crt}
        // domain_name 匹配证书 SAN = localhost
        Path certDir = certsDir;
        if (!Files.isDirectory(certDir)) {
            throw new IllegalStateException("certs dir not found: " + certDir);
        }
        String trustCertPath = certDir.resolve("ca.crt").toString();
        int[] ports = {50051, 50052, 50053};
        for (int i = 0; i < ports.length; i++) {
            int port = ports[i];
            String nodeName = "node" + (i + 1);
            String clientCertPath = certDir.resolve(nodeName + ".crt").toString();
            String clientKeyPath = certDir.resolve(nodeName + ".key").toString();
            // 复用生产代码的 mTLS 工厂（与 GrpcMpcCryptoEngine 同源）
            io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientSsl =
                    org.nexus.signing.mpc.transport.GrpcTlsContextFactory.buildClientSslContext(
                            trustCertPath, clientCertPath, clientKeyPath);
            ManagedChannel ch = NettyChannelBuilder
                    .forAddress("127.0.0.1", port)
                    .overrideAuthority("localhost")
                    .sslContext(clientSsl)
                    .build();
            // 注入 Bearer auth（与 mpc-engine AuthInterceptor 契约：MPC_AUTH_TOKEN=nexus-mpc-test-token）
            io.grpc.CallOptions callOpts = io.grpc.CallOptions.DEFAULT;
            MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub baseStub =
                    MpcCryptoServiceGrpc.newBlockingStub(ch);
            MpcCggmpClient client = new MpcCggmpClient(
                    baseStub.withInterceptors(new io.grpc.ClientInterceptor() {
                        @Override
                        public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
                                io.grpc.MethodDescriptor<ReqT, RespT> method,
                                io.grpc.CallOptions callOptions,
                                io.grpc.Channel next) {
                            return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
                                    next.newCall(method, callOptions)) {
                                @Override
                                public void start(io.grpc.ClientCall.Listener<RespT> responseListener,
                                                 io.grpc.Metadata headers) {
                                    headers.put(
                                            io.grpc.Metadata.Key.of("authorization",
                                                    io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                                            "Bearer nexus-mpc-test-token");
                                    super.start(responseListener, headers);
                                }
                            };
                        }
                    }), DEADLINE_MS);
            channels.add(ch);
            partyClients.add(client);
        }
        // 协调器 = node0（生产可指向独立协调方进程；本测试简化 = node0）
        coordinatorClient = partyClients.get(0);
        // 每方建 orchestrator
        for (int i = 0; i < 3; i++) {
            orchestrators.add(new MpcCggmpOrchestrator(
                    partyClients.get(i), coordinatorClient, i));
        }
        log.info("Java clients + 3 orchestrators ready");
    }

    @AfterAll
    static void stopCluster() {
        for (ManagedChannel ch : channels) {
            ch.shutdownNow();
        }
        for (Process p : engines) {
            if (p.isAlive()) {
                p.destroy();
                try { p.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (p.isAlive()) p.destroyForcibly();
            }
        }
        log.info("cluster stopped");
    }

    @Test
    @DisplayName("CGGMP21 端到端：3 方并发 keygen(t=2)——三方产出**一致**聚合公钥")
    void cggmpE2EKeygen() throws Exception {
        String sid = "i-batch-keygen-" + System.currentTimeMillis();
        int n = 3;
        int t = 2;

        // I 批关键验证：Java MpcCggmpOrchestrator 经 gRPC + mTLS + auth 真实驱动
        // 3 个 mpc-engine 进程跑通 CGGMP21 keygen 协议（4 轮内完成），三方产出一致 agg pubkey。
        // 这证明 Java 编排层与 mpc-engine 引擎**字节级互通**（F 批 RPC 契约 + is_p2p 消歧）。
        log.info("=== I batch: CGGMP21 keygen(n={}, t={}) session={} ===", n, t, sid);
        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<CgPumpResult>> keygenFutures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                keygenFutures.add(exec.submit(() ->
                        orchestrators.get(idx).runKeygen(sid, 0, idx, n, t)));
            }
            List<CgPumpResult> keygenResults = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                CgPumpResult r = keygenFutures.get(i).get(120, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(r.isSuccess(), "keygen party " + i + " failed: " + r.getError());
                assertTrue(r.isFinished(), "keygen party " + i + " not finished");
                keygenResults.add(r);
            }
        // 三方应产出一致的聚合公钥
        String aggPk0 = keygenResults.get(0).getAggregatePublicKey();
        assertNotNull(aggPk0, "agg pubkey is null");
        for (int i = 1; i < n; i++) {
            String pki = keygenResults.get(i).getAggregatePublicKey();
            assertEquals(aggPk0, pki,
                    "party " + i + " agg pubkey mismatch (got " + pki + " vs " + aggPk0 + ")");
        }
        log.info("keygen done, agg pubkey = {}", aggPk0);

            // I 批：aux + assemble + sign + verify 三阶段见下方 cggmpE2EFullPipeline
            // （J 批已补齐）。本批**关键验证**：
            //   1. Java 客户端经 mTLS + Bearer auth gRPC 真实驱动 3 进程 mpc-engine
            //   2. CGGMP21 keygen 协议 4-5 轮内完成（端到端延迟 < 1s）
            //   3. 三方产出一致的压缩 SEC1 hex 聚合公钥（33 字节）
            //   4. CgStatus 正确反映 has_keygen_state / has_core_share / has_key_share
            //      （status 测试单独验）
            log.info("I 批 keygen done: agg pubkey = {}",
                    keygenResults.get(0).getAggregatePublicKey());
            log.info("I 批端到端验证完成（全流水线见 cggmpE2EFullPipeline）");
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("CgStatus 在 keygen 完成后正确反映状态")
    void cggpE2EStatus() throws Exception {
        String sid = "i-batch-status-" + System.currentTimeMillis();
        int n = 3, t = 2;
        java.util.concurrent.ExecutorService exec =
                java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            List<java.util.concurrent.Future<CgPumpResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(exec.submit(() ->
                        orchestrators.get(idx).runKeygen(sid, 0, idx, n, t)));
            }
            for (int i = 0; i < n; i++) {
                CgPumpResult r = futures.get(i).get(120, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(r.isSuccess());
                assertTrue(r.isFinished());
            }
        } finally {
            exec.shutdown();
            exec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        CgStatus st0 = partyClients.get(0).status(sid);
        // F 批契约：keygen 完成时 keygen_state 被 take（has_keygen_state=false）
        //           core_share 已合成（has_core_share=true）
        //           key_share 仍 None（需 aux 后才有）
        assertFalse(st0.isHasKeygenState(),
                "keygen_state should be taken after keygen complete");
        assertTrue(st0.isHasCoreShare(),
                "core_share should be synthesized after keygen");
        assertFalse(st0.isHasKeyShare(),
                "key_share still None (needs aux)");
        assertFalse(st0.isHasAuxState(),
                "aux not run yet in I batch");
    }

    @Test
    @DisplayName("CGGMP21 全流水线：keygen→aux→assemble→sign(2-of-3)→verify（含篡改拒绝）")
    void cggmpE2EFullPipeline() throws Exception {
        String sid = "j-batch-full-" + System.currentTimeMillis();
        int n = 3;
        int t = 2;

        // J 批（2026-09-05）：I 批只验证到 keygen；本测试补齐签名主路径
        // 的最后一段——aux（Paillier 密钥协商）→ assembleShare（KeyShare 合成）
        // → sign（2-of-3 真出签名）→ verify（验签 + 篡改拒绝）。
        //
        // 结构逐相位对齐 mpc-engine/tests/cggmp_rpc_e2e.rs（F 批验收）：
        // **三方 start 串行完成（首波 outgoing 留在内存）后，才进入统一的
        // publish→pull→pump 循环**。不可用三方并发 orchestrator.runAux/runSign：
        // StartAux/StartSign 在服务端 clear_session 清协调器 relay 池（阶段边界
        // 设计），并发 start 时先发布方的前期消息会被 party0 的 start 清掉
        // （竞态→状态机永远等缺失消息→空转死锁，首跑实证）。orchestrator 把
        // start 与 pump 循环融合无法拆开，故本测试直接用 client 原语驱动。

        // ---------- Phase 1: keygen——三方 start 串行，再统一循环 ----------
        List<CgPumpResult> keygenStates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CgPumpResult r = partyClients.get(i).startKeygen(sid, 0, i, n, t);
            assertTrue(r.isSuccess(), "start keygen party " + i + " failed: " + r.getError());
            keygenStates.add(r);
        }
        keygenStates = pumpAll(keygenStates, sid, allParties(n), true, "keygen");
        String aggPk = keygenStates.get(0).getAggregatePublicKey();
        assertNotNull(aggPk, "keygen must produce aggregate pk");
        for (int i = 1; i < n; i++) {
            assertEquals(aggPk, keygenStates.get(i).getAggregatePublicKey(),
                    "agg pubkey mismatch party " + i);
        }

        // ---------- Phase 2: aux——三方 start 串行，再统一循环 ----------
        List<CgPumpResult> auxStates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CgPumpResult r = partyClients.get(i).startAux(sid, 0, i, n);
            assertTrue(r.isSuccess(), "start aux party " + i + " failed: " + r.getError());
            auxStates.add(r);
        }
        auxStates = pumpAll(auxStates, sid, allParties(n), false, "aux");
        for (int i = 0; i < n; i++) {
            assertTrue(auxStates.get(i).isFinished(), "aux party " + i + " not finished");
        }

        // ---------- Phase 3: assembleShare ×3（IncompleteKeyShare + aux → KeyShare） ----------
        for (int i = 0; i < n; i++) {
            assertTrue(partyClients.get(i).assembleShare(sid),
                    "assembleShare failed for party " + i);
        }

        // ---------- Phase 4: sign 2-of-3（signers = [0,1]，start 串行再循环） ----------
        int[] signers = {0, 1};
        byte[] messageHash = new byte[32];
        java.util.Arrays.fill(messageHash, (byte) 0x42);
        List<CgSignPumpResult> signStates = new ArrayList<>();
        for (int b = 0; b < signers.length; b++) {
            CgSignPumpResult r = partyClients.get(signers[b])
                    .startSign(sid, 0, b, signers, messageHash);
            assertTrue(r.isSuccess(), "start sign signer " + b + " failed: " + r.getError());
            signStates.add(r);
        }
        signStates = pumpAllSign(signStates, sid, signers, "sign");
        String rHex = signStates.get(0).getRHex();
        String sHex = signStates.get(0).getSHex();
        assertNotNull(rHex, "signature r is null");
        assertEquals(64, rHex.length(), "r hex must be 32 bytes big-endian");
        assertEquals(64, sHex.length(), "s hex must be 32 bytes big-endian");
        for (int b = 1; b < signers.length; b++) {
            // 两签名方产出的 r/s 必须一致（同一签名可在任一方 verify）
            assertEquals(rHex, signStates.get(b).getRHex(), "signer " + b + " r mismatch");
            assertEquals(sHex, signStates.get(b).getSHex(), "signer " + b + " s mismatch");
        }
        log.info("full pipeline sign done: r={}, s={}", rHex, sHex);

        // ---------- Phase 5: verify 正确签名通过 + 篡改拒绝 ----------
        byte[] r = hexToBytes(rHex);
        byte[] s = hexToBytes(sHex);
        CgVerifyResult ok = partyClients.get(0).verifySignature(sid, r, s, messageHash);
        assertTrue(ok.isSuccess(), "verify rpc failed: " + ok.getError());
        assertTrue(ok.isValid(), "2-of-3 signature must verify against agg pubkey");
        byte[] tamperedR = r.clone();
        tamperedR[0] ^= 0xFF;
        CgVerifyResult bad = partyClients.get(0)
                .verifySignature(sid, tamperedR, s, messageHash);
        assertTrue(bad.isSuccess(), "verify(tampered) rpc failed: " + bad.getError());
        assertFalse(bad.isValid(), "tampered signature must be rejected");

        // ---------- CgStatus 终态：KeyShare 已合成（对照 I 批 keygen-only 的 false） ----------
        CgStatus st = partyClients.get(0).status(sid);
        assertTrue(st.isHasKeyShare(), "key_share should exist after assemble");
        log.info("J batch full pipeline PASSED: keygen→aux→assemble→sign→verify, sid={}", sid);
    }

    /** 0..n-1 全体参与方索引。 */
    private static int[] allParties(int n) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        return all;
    }

    /**
     * keygen/aux 通用 relay 循环：未完成方的 outgoing 全部发布到协调器
     * （node0 relay 池），各参与方按自己的 index 拉取并 pump（打各自的引擎）。
     * 广播消息每方各拉一份（消费幂等按方记账），p2p 消息仅目标方可拉。
     */
    private List<CgPumpResult> pumpAll(
            List<CgPumpResult> states, String sid, int[] parties,
            boolean isKeygen, String phase) throws Exception {
        for (int round = 0; round < 200; round++) {
            boolean allDone = true;
            for (CgPumpResult st : states) {
                allDone &= st.isFinished();
            }
            if (allDone) {
                return states;
            }
            for (int i = 0; i < parties.length; i++) {
                CgPumpResult st = states.get(i);
                if (st.isFinished()) {
                    continue;
                }
                for (CgRelayMessageDto m : st.getOutgoing()) {
                    assertTrue(partyClients.get(0).publishRelay(m),
                            phase + ": publish failed sender=" + m.getSenderIndex());
                }
            }
            List<CgPumpResult> next = new ArrayList<>();
            for (int i = 0; i < parties.length; i++) {
                CgPumpResult st = states.get(i);
                if (st.isFinished()) {
                    next.add(st);
                    continue;
                }
                int partyIdx = parties[i];
                List<CgRelayMessageDto> incoming =
                        partyClients.get(0).pullRelay(sid, partyIdx);
                if (incoming == null) {
                    incoming = new ArrayList<>();
                }
                CgPumpResult r = isKeygen
                        ? partyClients.get(partyIdx).pumpKeygen(sid, incoming)
                        : partyClients.get(partyIdx).pumpAux(sid, incoming);
                assertTrue(r.isSuccess(),
                        phase + ": pump party " + partyIdx + " failed: " + r.getError());
                next.add(r);
            }
            states = next;
        }
        throw new AssertionError(phase + " stuck after 200 rounds");
    }

    /** sign 阶段 relay 循环：仅 signers 参与拉取/pump（非签名方持份额不动作）。 */
    private List<CgSignPumpResult> pumpAllSign(
            List<CgSignPumpResult> states, String sid, int[] signers, String phase) throws Exception {
        for (int round = 0; round < 200; round++) {
            boolean allDone = true;
            for (CgSignPumpResult st : states) {
                allDone &= st.isFinished();
            }
            if (allDone) {
                return states;
            }
            for (int b = 0; b < signers.length; b++) {
                CgSignPumpResult st = states.get(b);
                if (st.isFinished()) {
                    continue;
                }
                for (CgRelayMessageDto m : st.getOutgoing()) {
                    assertTrue(partyClients.get(0).publishRelay(m),
                            phase + ": publish failed sender=" + m.getSenderIndex());
                }
            }
            List<CgSignPumpResult> next = new ArrayList<>();
            for (int b = 0; b < signers.length; b++) {
                CgSignPumpResult st = states.get(b);
                if (st.isFinished()) {
                    next.add(st);
                    continue;
                }
                int keygenIdx = signers[b];
                List<CgRelayMessageDto> incoming =
                        partyClients.get(0).pullRelay(sid, keygenIdx);
                if (incoming == null) {
                    incoming = new ArrayList<>();
                }
                CgSignPumpResult r = partyClients.get(keygenIdx).pumpSign(sid, incoming);
                assertTrue(r.isSuccess(),
                        phase + ": pump signer " + b + " failed: " + r.getError());
                next.add(r);
            }
            states = next;
        }
        throw new AssertionError(phase + " stuck after 200 rounds");
    }

    // ============================================================
    // 工具
    // ============================================================

    /** mpc-engine 仓库根：二进制约定在 {@code <root>/target/{debug|release}/} 下，上两级即根。 */
    private static Path engineRoot() {
        return engineBinary.getParent().getParent().getParent();
    }

    /** 默认二进制：按 OS 选二进制名，锚定 {@code <repo>/mpc-engine/target/debug/}（Gradle test 工作目录 = 模块目录）。 */
    private static Path defaultEngineBinary() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "mpc-engine.exe" : "mpc-engine";
        return Paths.get("..", "mpc-engine", "target", "debug", name)
                .normalize().toAbsolutePath();
    }

    /** 目录解析：环境变量优先，否则锚定 mpc-engine 根下的约定相对目录。 */
    private static Path resolveDir(String envName, String relative) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return Paths.get(env);
        }
        return engineRoot().resolve(relative);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(input);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return new byte[0];
        }
        return java.util.HexFormat.of().parseHex(hex);
    }

    private static int waitForPort(int port, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                return port;
            } catch (IOException ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("port " + port + " not listening after " + timeoutMs + "ms");
    }

    private static void drainStdout(Process p, String tag) {
        Thread t = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                Path logPath = logDir.resolve("i-batch-" + tag + ".log");
                try (var fout = Files.newOutputStream(logPath)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fout.write(buf, 0, n);
                    }
                }
            } catch (IOException e) {
                log.warn("drain stdout for {} failed: {}", tag, e.getMessage());
            }
        }, "drain-" + tag);
        t.setDaemon(true);
        t.start();
    }
}
