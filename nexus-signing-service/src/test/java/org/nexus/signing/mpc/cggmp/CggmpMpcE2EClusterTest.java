package org.nexus.signing.mpc.cggmp;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.mpc.ColdWalletMultiSigService;
import org.nexus.signing.mpc.DefaultMpcService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.nexus.signing.mpc.MpcKeyGeneration;
import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcSignatureAggregator;
import org.nexus.signing.mpc.MpcSigner;
import org.nexus.signing.mpc.MpcWallet;
import org.nexus.signing.mpc.ThresholdPolicy;
import org.nexus.signing.mpc.persistence.MpcKeyShareStore;
import org.nexus.signing.mpc.persistence.MpcSessionRepository;
import org.nexus.signing.mpc.persistence.MpcWalletRepository;
import org.nexus.signing.mpc.router.MessageRouter;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.math.BigInteger;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // C 批：60s → 120s；P0-1 后再放宽到 300s。本地实测含 aux 的用例耗时
    // 66.3s / 103.7s（两轮波动 ±36%），而 CI 的 ubuntu-latest 只有 2 vCPU 却要跑
    // 3 个引擎进程 + 无 daemon 的 JVM，120s 余量不足（run #327 触顶失败，#326 侥幸通过）。
    // 抬高上限只增加失败延迟，不改变 happy path 耗时。
    private static final long DEADLINE_MS = 300_000L;

    private static Path engineBinary;
    private static final List<Process> engines = new ArrayList<>();
    private static final List<ManagedChannel> channels = new ArrayList<>();
    private static final List<MpcCggmpClient> partyClients = new ArrayList<>();
    private static MpcCggmpClient coordinatorClient;
    private static final List<MpcCggmpOrchestrator> orchestrators = new ArrayList<>();
    private static int port1;
    /**
     * 三个引擎的 host 端口——**从各自 nodeN.json 的 listen_addr 解析**（2026-10-09）。
     * 此前硬编码 50051-50053，导致本机/WSL 无法运行本测试（50053 常被其他服务占用）
     * 且与 {@code MPC_CONFIG_DIR} 指向的自定义配置集不一致。现与配置同源：
     * 用新生成器产出 51051+ 的配置集即可在任意空闲端口段运行
     * （{@code bash scripts/gen-mpc-engine-configs.sh --layout native --base-port 51051 -o <dir>}）。
     */
    private static int[] ports;
    /**
     * party_index → 节点配置文件（**按内容发现**，2026-10-09 CI 回归修复）。
     *
     * <p>为什么不能按文件名猜：CI 用的 {@code start-mpc-cluster.sh --setup-only} 产出
     * **1-based 文件名 + 0-based party_index**（{@code node1.json} 内是 {@code party_index=0}）；
     * 而 {@code gen-mpc-engine-configs.sh} 产出 0-based 文件名（{@code node0.json}=party0）。
     * 曾按"优先 node{i}.json"解析 → party 1/2 取到 node1/node2（实为 party 0/1）→
     * 同一引擎被驱动两次 → 状态机报 {@code AttemptToOverwriteReceivedMsg}，CI 5/5 全红
     * （本机用 0-based 配置集恰好看不出）。现一律**读文件里的 party_index**，两种命名都对。</p>
     */
    private static java.util.Map<Integer, Path> nodeConfigs;

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
        nodeConfigs = discoverNodeConfigs(configDir);
        for (int i = 1; i <= 3; i++) {
            Path configPath = resolveNodeConfig(i - 1);
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
        ports = resolveEnginePorts(configDir);
        port1 = waitForPort(ports[0], 15_000);
        waitForPort(ports[1], 5_000);
        waitForPort(ports[2], 5_000);
        log.info("3 mpc-engine nodes up: 127.0.0.1:{}", ports[0] + "," + ports[1] + "," + ports[2]);

        // 建 3 个 mTLS channel（用项目自带 GrpcTlsContextFactory，与生产集群通道一致）
        // 证书路径：<mpc-engine>/certs/{nodeN.crt, nodeN.key, ca.crt}
        // domain_name 匹配证书 SAN = localhost
        Path certDir = certsDir;
        if (!Files.isDirectory(certDir)) {
            throw new IllegalStateException("certs dir not found: " + certDir);
        }
        String trustCertPath = certDir.resolve("ca.crt").toString();
        for (int i = 0; i < ports.length; i++) {
            int port = ports[i];
            String nodeName = "node" + (i + 1);
            String clientCertPath = certDir.resolve(nodeName + ".crt").toString();
            String clientKeyPath = certDir.resolve(nodeName + ".key").toString();
            // 复用生产代码的 mTLS 工厂（与生产集群通道同源）
            io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientSsl =
                    org.nexus.signing.mpc.transport.GrpcTlsContextFactory.buildClientSslContext(
                            trustCertPath, clientCertPath, clientKeyPath);
            // CI 长尾修复（2026-09-06）：keygen→aux 阶段间隙约 30s 无 RPC 时，
            // NettyChannelBuilder 默认 idleTimeout(30s) 关闭连接——下一调用
            // （startAux party1）落在已关连接上报 UNKNOWN/HTTP status 200、
            // 且重连后的请求约 30s 后才到引擎（ab91ea5 CI run 33998883106
            // 三节点日志时间线实证：party1 StartAux 23:55:16 才到而 party0
            // 23:54:45 已回）。显式 keepalive + 禁 idle 修复；本地无 JDK
            // 环境（Corretto 目录消失），由 CI 实证。
            ManagedChannel ch = NettyChannelBuilder
                    .forAddress("127.0.0.1", port)
                    .overrideAuthority("localhost")
                    .keepAliveTime(10, java.util.concurrent.TimeUnit.SECONDS)
                    .keepAliveTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .keepAliveWithoutCalls(true)
                    .idleTimeout(java.util.concurrent.TimeUnit.DAYS.toSeconds(1), java.util.concurrent.TimeUnit.SECONDS)
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
                CgPumpResult r = keygenFutures.get(i).get(360, java.util.concurrent.TimeUnit.SECONDS);
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
        // 2026-09-07 完善批：与 fullPipeline 同款 client 原语 + 传输抖动重试
        // （此前走 orchestrator.runKeygen——3be3993 轮 gRPC UNKNOWN 级联
        // 挂在此处；改用 client 原语与重试保持全类一致性）。
        // 三方 start 串行（keygen 守卫幂等：盘上有产物直接恢复；重试安全），
        // 再统一 pumpAll 循环。
        List<CgPumpResult> keygenStates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CgPumpResult r = partyClients.get(i).startKeygen(sid, 0, i, n, t);
            if (!r.isSuccess() && r.getError() != null
                    && r.getError().contains("HTTP status code")) {
                log.warn("startKeygen party {} transport glitch, retry once", i);
                r = partyClients.get(i).startKeygen(sid, 0, i, n, t);
            }
            assertTrue(r.isSuccess(), "start keygen party " + i + " failed: " + r.getError());
            keygenStates.add(r);
        }
        keygenStates = pumpAll(keygenStates, sid, allParties(n), true, "keygen");
        for (int i = 0; i < n; i++) {
            assertTrue(keygenStates.get(i).isFinished(), "keygen party " + i + " not finished");
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

        // ---------- Phase 2: aux——三方 start 并发（三轮 CI 实证修正） ----------
        // aux 首轮含 PregeneratedPrimes 安全素数生成（2048bit，CI 慢机每方
        // ~80s）——串行 start 会把 3×80s 叠加成 ~4min，party2 的 startAux
        // 发起时 party0 的连接已空闲>30s 报 HTTP 200 UNKNOWN（34024439200
        // 轮三节点日志：23.6s/43.9s/46:39 阶梯到达）。并发 start = 三方
        // 并行算素数（总时长 = max 而非 sum），且连接全程活跃无空闲窗口。
        // 服务端 StartAux 幂等守卫（registry 已建状态机跳过）容忍并发重试；
        // clear_session 阶段边界在首方 start 时清一次池——并发方首波 outgoing
        // 在 start 全部返回后才进入 pumpAll 循环发布，时序安全。
        //
        // 2026-09-07 完善批：gRPC UNKNOWN 重试一次——三轮 CI 同症状
        // （788ee89/82eb96a/3be3993 轮：party2 的响应回传报
        // "HTTP status code 200"，引擎日志证请求已到达且处理完成——
        // 是 Netty 连接层抖动，非协议错误）。重试安全依据：StartAux
        // 幂等守卫——第一次已把 aux 状态机建进 registry 的话，重试走
        // "已存在跳过"路径返回同结果；第一次没到的话重试正常执行。
        java.util.concurrent.ExecutorService auxExec =
                java.util.concurrent.Executors.newFixedThreadPool(n);
        List<CgPumpResult> auxStates;
        try {
            List<java.util.concurrent.Future<CgPumpResult>> auxFutures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                auxFutures.add(auxExec.submit(() -> {
                    CgPumpResult r = partyClients.get(idx).startAux(sid, 0, idx, n);
                    if (!r.isSuccess() && r.getError() != null
                            && r.getError().contains("HTTP status code")) {
                        log.warn("startAux party {} hit transport glitch ({}), retrying once (idempotent guard)",
                                idx, r.getError());
                        r = partyClients.get(idx).startAux(sid, 0, idx, n);
                    }
                    return r;
                }));
            }
            auxStates = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                CgPumpResult r = auxFutures.get(i).get(360, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(r.isSuccess(), "start aux party " + i + " failed: " + r.getError());
                auxStates.add(r);
            }
        } finally {
            auxExec.shutdown();
            auxExec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
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

    // ============================================================
    // P0-1：生产路径 E2E（MpcEngineRouter → CggmpMpcCryptoEngine → 集群驱动）
    // ============================================================

    /**
     * P0-1 生产路径 E2E（B2 验收口径：3 节点 2-of-3 真实签名 + 无单进程全份额路径）。
     *
     * <p>与上方用例的差异：不手工拼 client/orchestrator，而是走**生产装配链**——
     * {@link MpcEngineRouter}（多端点 + mTLS + Bearer + overrideAuthority +
     * keepalive，绕开手工 channel 的同款配置）→ {@link MpcCggmpClusterConfig}
     * （cggmp-enabled=true 的 fail-closed 装配）→ {@link CggmpClusterSessionDriver}
     * → {@link CggmpMpcCryptoEngine}（SPI 入口，签名方 = signers="0,1"）。</p>
     *
     * <p>断言链：</p>
     * <ol>
     *   <li>份额隔离——每节点 {@code cggmp/<sid>/keyshare.bin} 存在、NXC1 信封、
     *       三节点密文两两不同；无 GG20 全量快照 {@code session-<sid>.json} /
     *       {@code my-share-<sid>.json}（B2"无单进程全份额路径"）</li>
     *   <li>2-of-3 签名 → aggregate 拆 r/s → verify 通过 + 篡改拒绝</li>
     *   <li>同 wallet session 二次签名——份额复用（"keygen 一次、长期签名"）
     *       且 eid 序号递增（ExecutionId 唯一性契约）</li>
     * </ol>
     */
    @Test
    @DisplayName("P0-1 生产路径：Router→CggmpMpcCryptoEngine 2-of-3 + 份额隔离 + 份额复用")
    void cggmpE2EProductionPath() throws Exception {
        // ---------- 生产装配：MpcEngineRouter（3 端点，生产同款 TLS/认证配置） ----------
        MpcEngineRouter router = new MpcEngineRouter();
        ReflectionTestUtils.setField(router, "endpoints", endpointSpec());
        ReflectionTestUtils.setField(router, "distributedMode", true);
        ReflectionTestUtils.setField(router, "usePlaintext", false);
        ReflectionTestUtils.setField(router, "tlsTrustCertPath",
                certsDir.resolve("ca.crt").toString());
        ReflectionTestUtils.setField(router, "tlsClientCertPath",
                certsDir.resolve("node1.crt").toString());
        ReflectionTestUtils.setField(router, "tlsClientKeyPath",
                certsDir.resolve("node1.key").toString());
        ReflectionTestUtils.setField(router, "tlsOverrideAuthority", "localhost");
        ReflectionTestUtils.setField(router, "authToken", "nexus-mpc-test-token");
        router.init();
        assertEquals(3, router.getEndpointCount(), "3 端点应全部建链");

        // ---------- 生产 bean：MpcCggmpClusterConfig（cggmp-enabled=true fail-closed） ----------
        MpcCggmpClusterConfig clusterConfig = new MpcCggmpClusterConfig(router);
        ReflectionTestUtils.setField(clusterConfig, "cggmpDeadlineMs", DEADLINE_MS);
        ReflectionTestUtils.setField(clusterConfig, "cggmpEnabled", true);
        CggmpClusterSessionDriver driver = clusterConfig.cggmpClusterSessionDriver();
        assertNotNull(driver, "3 端点应装配出集群驱动");
        assertEquals(3, driver.parties());

        // ---------- 生产 SPI：CggmpMpcCryptoEngine（signers="0,1" = 2-of-3） ----------
        @SuppressWarnings("unchecked")
        ObjectProvider<CggmpClusterSessionDriver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driver);
        CggmpMpcCryptoEngine engine = new CggmpMpcCryptoEngine(provider);
        ReflectionTestUtils.setField(engine, "cggmpEnabled", true);
        ReflectionTestUtils.setField(engine, "signersConfig", "0,1");

        try {
            String walletId = "wallet-p01-" + System.currentTimeMillis();
            String sid = CggmpMpcCryptoEngine.walletSessionId(walletId);
            assertTrue(sid.matches("[0-9a-zA-Z-]+"), "session id 字符集: " + sid);

            // ---------- dkg = keygen → aux → assemble ×3 ----------
            DkgResponse dkg = engine.dkg(new DkgRequest(sid, 2, 3, 0, "secp256k1", List.of()));
            assertTrue(dkg.isSuccess(), "dkg failed: " + dkg.getError());
            String aggPk = dkg.getPublicKey();
            assertEquals(66, aggPk.length(), "聚合公钥 = 压缩 SEC1 33 字节 hex");
            assertNull(dkg.getKeyShare(), "份额不得离开引擎进程（恒 null）");
            assertTrue(driver.status(sid).isHasKeyShare(), "assemble 后 key_share 应存在");

            // ---------- 份额隔离（B2：无单进程全份额路径） ----------
            List<String> shareDigests = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                Path sessionsDir = dataDir.resolve("node" + i).resolve("sessions");
                Path keyshare = sessionsDir.resolve("cggmp").resolve(sid).resolve("keyshare.bin");
                assertTrue(Files.isRegularFile(keyshare),
                        "node" + i + " keyshare.bin 应存在: " + keyshare);
                byte[] magic = new byte[4];
                try (InputStream in = Files.newInputStream(keyshare)) {
                    assertEquals(4, in.read(magic), "keyshare 头部读取");
                }
                assertEquals("NXC1", new String(magic, StandardCharsets.US_ASCII),
                        "node" + i + " keyshare 应为 NXC1 加密信封");
                shareDigests.add(java.util.HexFormat.of().formatHex(
                        sha256(Files.readAllBytes(keyshare))));
                // GG20 全量会话快照（D 批 LocalKey 落盘命名）不得出现
                assertFalse(Files.exists(sessionsDir.resolve("session-" + sid + ".json")),
                        "node" + i + " 不得有 GG20 全量会话快照 session-" + sid + ".json");
                assertFalse(Files.exists(sessionsDir.resolve("my-share-" + sid + ".json")),
                        "node" + i + " 不得有 GG20 份额文件 my-share-" + sid + ".json");
            }
            assertNotEquals(shareDigests.get(0), shareDigests.get(1),
                    "node1/node2 份额密文不得相同（无全量份额复制）");
            assertNotEquals(shareDigests.get(1), shareDigests.get(2),
                    "node2/node3 份额密文不得相同（无全量份额复制）");
            assertNotEquals(shareDigests.get(0), shareDigests.get(2),
                    "node1/node3 份额密文不得相同（无全量份额复制）");

            // ---------- 2-of-3 签名 → aggregate 拆 r/s → verify ----------
            byte[] msgHash1 = sha256("p01-production-path".getBytes(StandardCharsets.UTF_8));
            String hashHex1 = java.util.HexFormat.of().formatHex(msgHash1);
            SignResponse sign1 = engine.sign(
                    new SignRequest(sid, aggPk, "", hashHex1, 0, List.of()));
            assertTrue(sign1.isSuccess(), "sign failed: " + sign1.getError());
            String concat1 = sign1.getPartialSignature();
            assertEquals(128, concat1.length(), "r||s = 64 字节 hex");

            AggregateResponse agg1 = engine.aggregate(
                    new AggregateRequest(sid, aggPk, hashHex1, List.of(concat1)));
            assertTrue(agg1.isSuccess(), "aggregate failed: " + agg1.getError());
            assertEquals(concat1.substring(0, 64), agg1.getR(), "r 拆分");
            assertEquals(concat1.substring(64), agg1.getS(), "s 拆分");

            CgVerifyResult ok1 = driver.verify(
                    sid, hexToBytes(agg1.getR()), hexToBytes(agg1.getS()), msgHash1);
            assertTrue(ok1.isSuccess(), "verify rpc failed: " + ok1.getError());
            assertTrue(ok1.isValid(), "2-of-3 生产路径签名必须验签通过");

            byte[] tamperedR = hexToBytes(agg1.getR());
            tamperedR[0] ^= 0xFF;
            CgVerifyResult bad = driver.verify(sid, tamperedR, hexToBytes(agg1.getS()), msgHash1);
            assertTrue(bad.isSuccess(), "verify(tampered) rpc failed: " + bad.getError());
            assertFalse(bad.isValid(), "篡改签名必须拒绝");

            assertTrue(engine.healthCheck(), "healthCheck 应通过（集群在线）");

            // ---------- 份额复用：同 wallet session 二次签名（eid 序号递增） ----------
            byte[] msgHash2 = sha256("p01-second-tx".getBytes(StandardCharsets.UTF_8));
            String hashHex2 = java.util.HexFormat.of().formatHex(msgHash2);
            SignResponse sign2 = engine.sign(
                    new SignRequest(sid, aggPk, "", hashHex2, 0, List.of()));
            assertTrue(sign2.isSuccess(), "second sign failed: " + sign2.getError());
            String concat2 = sign2.getPartialSignature();
            assertEquals(128, concat2.length(), "第二笔 r||s = 64 字节 hex");
            assertNotEquals(concat1, concat2, "不同消息的签名不得相同");
            CgVerifyResult ok2 = driver.verify(sid,
                    hexToBytes(concat2.substring(0, 64)),
                    hexToBytes(concat2.substring(64)), msgHash2);
            assertTrue(ok2.isSuccess(), "verify#2 rpc failed: " + ok2.getError());
            assertTrue(ok2.isValid(), "第二笔签名必须验签通过（同份额复用）");

            log.info("P0-1 生产路径 E2E PASSED: wallet={}, sid={}, aggPk={}", walletId, sid, aggPk);
        } finally {
            router.shutdown();
        }
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
        // 2026-09-22：失败信息补充各参与方状态。
        // 背景：本断言在 CI 上偶发失败（"stuck after 200 rounds"）——
        // 同一提交前后两次运行均通过，属抖动而非稳定缺陷。但原信息只说
        // "卡住"，无法判断是某一方未完成、还是消息未投递（outgoing 非空
        // 但无人消费）。此处打出每方的 finished / outgoing 计数，
        // 使下次复现可直接定位参与方与消息堆积点。
        // 注意：**仅增加诊断，未改动轮次预算与收敛逻辑** ——
        // 在没有确凿证据前提高轮次上限只会掩盖问题。
        StringBuilder diag = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            CgPumpResult st = states.get(i);
            diag.append("\n  party=").append(parties[i])
                    .append(" finished=").append(st.isFinished())
                    .append(" outgoing=")
                    .append(st.getOutgoing() == null ? 0 : st.getOutgoing().size());
        }
        throw new AssertionError(phase + " stuck after 200 rounds; 各方状态:" + diag);
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
    // 冷钱包业务链 E2E（2026-10-09 入口层配套）
    // ============================================================

    /**
     * 冷钱包业务链端到端：真实引擎集群上跑 **DKG 编排（{@link DefaultMpcService#generateKeyShare}）
     * → 转账受理 → MPC 签名 → 聚合广播 → 状态查询**。
     *
     * <p>覆盖两条此前从未被运行的业务段：</p>
     * <ol>
     *   <li>{@code generateKeyShare} 对 CGGMP21 的「无份额」路径——该路径此前无条件
     *       构造 {@code MpcKeyShare} 会抛 NPE（CGGMP21 份额驻留引擎、DkgResponse.keyShare
     *       恒 null），即 DKG 编排对唯一存活路径必然失败；本用例是该修复的回归门禁；</li>
     *   <li>{@link ColdWalletMultiSigService} 的 init/sign/broadcast/status——
     *       审计发现其「零调用方、无 HTTP 入口」（业务链没接出去），本批接出并在此实测。</li>
     * </ol>
     *
     * <p>边界（有意 mock，避免重复覆盖）：仅**审批策略行为**（{@link MpcApprovalPolicy}，
     * 有独立单测）。**广播段为真实 HTTP**：{@link NodeController} 按生产代码发
     * {@code POST /sendTransaction}，测试内起"链节点"桩（{@link StubChainNode}）并在
     * 节点侧用聚合公钥独立验签——业务链跑通即蕴含"MPC 签名可被链节点验签"。
     * 真链上打包/共识仍需真实节点环境。</p>
     */
    @Test
    @DisplayName("冷钱包业务链：DKG 编排 → 受理 → 签名 → 广播 → 状态（真实 3 引擎集群）")
    void coldWalletBusinessChainE2E() throws Exception {
        // ---------- 生产装配（与 P0-1 用例同款）：Router → ClusterConfig → Driver → Engine ----------
        MpcEngineRouter router = new MpcEngineRouter();
        ReflectionTestUtils.setField(router, "endpoints", endpointSpec());
        ReflectionTestUtils.setField(router, "distributedMode", true);
        ReflectionTestUtils.setField(router, "usePlaintext", false);
        ReflectionTestUtils.setField(router, "tlsTrustCertPath",
                certsDir.resolve("ca.crt").toString());
        ReflectionTestUtils.setField(router, "tlsClientCertPath",
                certsDir.resolve("node1.crt").toString());
        ReflectionTestUtils.setField(router, "tlsClientKeyPath",
                certsDir.resolve("node1.key").toString());
        ReflectionTestUtils.setField(router, "tlsOverrideAuthority", "localhost");
        ReflectionTestUtils.setField(router, "authToken", "nexus-mpc-test-token");
        router.init();

        MpcCggmpClusterConfig clusterConfig = new MpcCggmpClusterConfig(router);
        ReflectionTestUtils.setField(clusterConfig, "cggmpDeadlineMs", DEADLINE_MS);
        ReflectionTestUtils.setField(clusterConfig, "cggmpEnabled", true);
        CggmpClusterSessionDriver driver = clusterConfig.cggmpClusterSessionDriver();
        assertNotNull(driver, "3 端点应装配出集群驱动");

        @SuppressWarnings("unchecked")
        ObjectProvider<CggmpClusterSessionDriver> driverProvider = mock(ObjectProvider.class);
        when(driverProvider.getIfAvailable()).thenReturn(driver);
        CggmpMpcCryptoEngine engine = new CggmpMpcCryptoEngine(driverProvider);
        ReflectionTestUtils.setField(engine, "cggmpEnabled", true);
        ReflectionTestUtils.setField(engine, "signersConfig", "0,1");

        // ---------- 参与方（= 3 引擎端点；CGGMP21 下 publicKeyShareHex 无意义，恒空串） ----------
        List<MpcParticipant> participants = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            participants.add(new MpcParticipant("party-" + i, "127.0.0.1:" + ports[i], ""));
        }
        List<MpcParticipant> online = participants.stream()
                .map(p -> p.withOnline(true))
                .collect(Collectors.toList());

        // ---------- DefaultMpcService：非 MPC 依赖 mock（持久化/传输/路由不参与本链断言） ----------
        DefaultMpcService mpcService = new DefaultMpcService(
                engine,
                mock(MpcSessionRepository.class),
                mock(MpcWalletRepository.class),
                mock(MpcKeyShareStore.class),
                mock(MpcTransport.class),
                mock(MessageRouter.class));

        String walletId = "wallet-biz-" + System.currentTimeMillis();
        String sid = CggmpMpcCryptoEngine.walletSessionId(walletId);

        // ---------- 1. DKG 编排（曾因 keyShare==null 必抛 NPE；本组断言即回归门禁） ----------
        MpcKeyGeneration.DkgResult dkg = mpcService.generateKeyShare(
                sid, 2, 3, 0, participants.get(0).getParticipantId(), "secp256k1", participants);
        assertNotNull(dkg.getJointPublicKeyHex(), "DKG 应返回聚合公钥");
        assertEquals(66, dkg.getJointPublicKeyHex().length(), "聚合公钥 = 压缩 SEC1 33 字节 hex");
        assertTrue(dkg.getShares().isEmpty(), "CGGMP21 份额驻留引擎进程，Java 侧不得持有");
        assertTrue(driver.status(sid).isHasKeyShare(), "集群侧 key_share 应已装配");

        // ---------- 2. 冷钱包编排：受理 → 签名 → 广播 ----------
        // 广播段为**真实 HTTP**（2026-10-09 起不再是 mock）：NodeController 按生产代码
        // 发 POST /sendTransaction，测试内以 JDK HttpServer 起"链节点"桩，并在**节点侧**
        // 用聚合公钥独立验签——只有验签通过才回 code=2000。即
        //   "Java 编排 → 引擎 MPC 签名 → HTTP 广播 → 节点验签" 形成密码学闭环。
        // （真链上打包/共识仍需真实节点环境；本桩不实现交易池/出块。）
        MpcApprovalPolicy policy = mock(MpcApprovalPolicy.class);
        when(policy.canSign(any(), anyList())).thenReturn(true);
        when(policy.isAddressWhitelisted(anyString())).thenReturn(true);
        when(policy.getColdWalletPolicy()).thenReturn(new ThresholdPolicy(2, 3));

        // 转账要素提取为变量：节点桩按同一算式复算待签数据哈希（与服务 buildTransactionHex 同式）
        String fromAddress = "0xFrom" + walletId;
        String toAddress = "0xTo" + walletId;
        java.math.BigDecimal amount = new java.math.BigDecimal("1.5");
        String asset = "USDT";
        String requestId = "req-e2e-" + walletId;
        String expectedTxHash = "0xE2E" + walletId;
        byte[] expectedMessageHash = java.security.MessageDigest.getInstance("SHA-256").digest(
                ("TX:" + fromAddress + ":" + toAddress + ":" + amount + ":" + asset + ":" + requestId)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try (StubChainNode stubNode = new StubChainNode(
                dkg.getJointPublicKeyHex(), expectedMessageHash, expectedTxHash)) {
            NodeController node = new NodeController();
            ReflectionTestUtils.setField(node, "ip", "127.0.0.1:" + stubNode.port());

            ColdWalletMultiSigService service = new ColdWalletMultiSigService(
                    mock(MpcSigner.class), mock(MpcSignatureAggregator.class), policy, node);
            ReflectionTestUtils.setField(service, "cggmpEngine", engine);

            MpcWallet wallet = new MpcWallet();
            wallet.setWalletId(walletId);
            wallet.setThreshold(2);
            wallet.setParticipants(participants.stream()
                    .map(MpcParticipant::getParticipantId)
                    .collect(Collectors.toList()));
            wallet.setPublicKey(dkg.getJointPublicKeyHex());
            service.registerWallet(wallet);

            String transferSid = service.initMultiSigTransfer(walletId,
                    fromAddress, toAddress, amount, asset, requestId, online);
            assertNotNull(transferSid, "受理应返回转账会话 ID");
            assertEquals(ColdWalletMultiSigService.TransferStatus.PENDING,
                    service.getSessionStatus(transferSid), "受理后应为 PENDING");

            service.participantSign(transferSid);   // 真实 CGGMP21 2-of-3：引擎内产出 r||s
            assertEquals(ColdWalletMultiSigService.TransferStatus.SIGNING,
                    service.getSessionStatus(transferSid), "签名后应进入 SIGNING（AGGREGATING）");

            String txHash = service.aggregateAndBroadcast(transferSid);
            assertEquals(expectedTxHash, txHash, "应返回节点接口的链上哈希");
            assertEquals(ColdWalletMultiSigService.TransferStatus.COMPLETED,
                    service.getSessionStatus(transferSid), "广播后应 COMPLETED");
            assertNull(service.getFailureReason(transferSid), "成功路径不应有失败原因");

            // 广播段证据：真实 HTTP 命中一次 + 节点侧用聚合公钥验签通过
            assertEquals(1, stubNode.hits(), "广播应命中节点 /sendTransaction 恰好一次");
            assertTrue(stubNode.lastSignatureValid(),
                    "节点侧必须用聚合公钥验签通过（ECDSA/secp256k1 密码学闭环）");

            // 交叉对照：同一 r/s 在引擎侧用同一摘要应通过、换错摘要必须拒绝。
            // 这条同时钉住"签名确实绑定消息"——2026-10-09 修引擎双重哈希前，
            // 引擎实际签 SHA256(SHA256(tx))，引擎自验通过而外部验签必拒。
            String sigHex = stubNode.lastTraninfo();
            byte[] rBytes = hexToBytes(sigHex.substring(0, 64));
            byte[] sBytes = hexToBytes(sigHex.substring(64));
            byte[] wrongHash = new byte[32];
            Arrays.fill(wrongHash, (byte) 0x11);
            assertTrue(driver.verify(sid, rBytes, sBytes, expectedMessageHash).isValid(),
                    "引擎侧用同一摘要应验签通过（同一 z 口径）");
            assertFalse(driver.verify(sid, rBytes, sBytes, wrongHash).isValid(),
                    "引擎侧换错摘要必须拒绝（防'签名不绑定消息'回归）");

            log.info("冷钱包业务链 E2E PASSED: wallet={}, transferSid={}, aggPk={}..., txHash={}, "
                            + "nodeSideVerify={}",
                    walletId, transferSid, dkg.getJointPublicKeyHex().substring(0, 16), txHash,
                    stubNode.lastSignatureValid());
        }
    }

    /**
     * "链节点"桩：真实 HTTP + 节点侧验签（2026-10-09）。
     *
     * <p>只实现被测链路需要的一个端点：{@code POST /sendTransaction}（form 参数
     * {@code traninfo=<签名 hex>}，与生产 {@link NodeController} 的请求形态一致）。
     * 收到后用**聚合公钥**独立验签（ECDSA/secp256k1，BouncyCastle）：
     * 通过 → {@code {"code":2000,"data":"<txHash>"}}；不通过 → {@code code=5000}。
     * 因此"业务链跑完"蕴含"MPC 签名能被链节点验签"——这正是真链上打包前的第一道关。</p>
     *
     * <p>不实现（需真实节点环境）：交易池、出块/共识、状态机更新。</p>
     */
    private static final class StubChainNode implements AutoCloseable {

        private final com.sun.net.httpserver.HttpServer server;
        private final String expectedAggPkHex;
        private final byte[] expectedMessageHash;
        private final String txHash;
        private final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean lastSignatureValid;
        private volatile String lastTraninfo;

        StubChainNode(String expectedAggPkHex, byte[] expectedMessageHash, String txHash)
                throws IOException {
            this.expectedAggPkHex = expectedAggPkHex;
            this.expectedMessageHash = expectedMessageHash;
            this.txHash = txHash;
            this.server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            this.server.createContext("/sendTransaction", this::handleSendTransaction);
            this.server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        int hits() {
            return hits.get();
        }

        boolean lastSignatureValid() {
            return lastSignatureValid;
        }

        String lastTraninfo() {
            return lastTraninfo;
        }

        private void handleSendTransaction(com.sun.net.httpserver.HttpExchange ex)
                throws IOException {
            hits.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String traninfo = null;
            for (String kv : body.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0 && "traninfo".equals(kv.substring(0, eq))) {
                    traninfo = java.net.URLDecoder.decode(kv.substring(eq + 1),
                            java.nio.charset.StandardCharsets.UTF_8);
                }
            }
            lastTraninfo = traninfo;
            lastSignatureValid = verifySignature(traninfo);

            String json;
            if (lastSignatureValid) {
                json = "{\"code\":2000,\"message\":\"ok\",\"data\":\"" + txHash + "\"}";
            } else {
                // 节点侧拒绝：长度非法 / 聚合公钥不可用 / ECDSA 验签不过——都不接受上链
                json = "{\"code\":5000,\"message\":\"bad signature\",\"data\":null}";
                log.warn("[stub-node] 拒绝广播：签名未通过聚合公钥验签（traninfo.len={}）",
                        traninfo == null ? -1 : traninfo.length());
            }
            byte[] out = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        }

        /**
         * 节点侧验签（签名 = r||s 64 字节 hex，消息 = 待签数据哈希 32 字节）。
         *
         * <p>用**仓库规范算法**（与 {@code DefaultMpcService#verifyEcdsaSignature} 同一数学：
         * u1 = z·s⁻¹, u2 = r·s⁻¹, R = u1·G + u2·Q, 判 R.x mod n == r），含公钥在曲线上、
         * r/s 范围检查。刻意不用 BC 的 {@code ECDSASigner}：那会引入"验签库自身行为"
         * 这一变量；手写算法与生产验签实现逐行对齐，失败时也能打印中间量定位。</p>
         */
        private boolean verifySignature(String signatureHex) {
            if (signatureHex == null || signatureHex.length() != 128) {
                return false;
            }
            try {
                X9ECParameters params = ECNamedCurveTable.getByName("secp256k1");
                BigInteger n = params.getN();
                ECPoint q = params.getCurve().decodePoint(hexToBytes(expectedAggPkHex));
                if (!q.isValid() || q.isInfinity()) {
                    log.warn("stub chain node: aggregate public key invalid/at infinity");
                    return false;
                }
                BigInteger r = new BigInteger(1, hexToBytes(signatureHex.substring(0, 64)));
                BigInteger s = new BigInteger(1, hexToBytes(signatureHex.substring(64)));
                BigInteger z = new BigInteger(1, expectedMessageHash);
                if (r.signum() <= 0 || r.compareTo(n) >= 0 || s.signum() <= 0 || s.compareTo(n) >= 0) {
                    return false;
                }
                BigInteger sInv = s.modInverse(n);
                BigInteger u1 = z.multiply(sInv).mod(n);
                BigInteger u2 = r.multiply(sInv).mod(n);
                ECPoint bigR = org.bouncycastle.math.ec.ECAlgorithms
                        .sumOfTwoMultiplies(params.getG(), u1, q, u2).normalize();
                if (bigR.isInfinity()) {
                    return false;
                }
                BigInteger rPrime = bigR.getAffineXCoord().toBigInteger().mod(n);
                boolean ok = rPrime.equals(r);
                if (!ok) {
                    // 典型成因：z 口径不符（引擎把摘要又哈希了一遍 → 见 mpc-engine
                    // cggmp.rs::build_data_to_sign 的 2026-10-09 修复）或 r/s 被篡改
                    log.warn("[stub-node] ECDSA 验签不通过：z={} r={} s={} r'x mod n={}",
                            java.util.HexFormat.of().formatHex(expectedMessageHash),
                            String.format("%064x", r), String.format("%064x", s),
                            String.format("%064x", rPrime));
                }
                return ok;
            } catch (RuntimeException e) {
                log.warn("stub chain node: signature parse/verify error: {}", e.getMessage());
                return false;
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
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

    /** 端点串（与 {@link #ports} 同源）：{@code 127.0.0.1:p0,127.0.0.1:p1,127.0.0.1:p2}。 */
    private static String endpointSpec() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ports.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("127.0.0.1:").append(ports[i]);
        }
        return sb.toString();
    }

    /**
     * 从 {@code nodeN.json} 的 listen_addr 解析三个引擎端口（与配置集同源，
     * 不再硬编码——见 {@link #ports} 字段注释）。
     */
    /**
     * 扫描 {@code node*.json}，按**内容 party_index** 建立索引（兼容两种命名约定）。
     *
     * <p>命名风险见 {@link #nodeConfigs} 注释：文件名编号在不同生成器下相差 1，
     * 只有文件内容的 {@code party_index} 是权威。若配置里没有该字段（异常配置），
     * 退回"文件名数字 - 1 = party_index"的历史约定并告警。</p>
     */
    private static java.util.Map<Integer, Path> discoverNodeConfigs(Path configDir) throws IOException {
        java.util.Map<Integer, Path> byParty = new java.util.LinkedHashMap<>();
        java.util.List<Path> files;
        try (java.util.stream.Stream<Path> stream = Files.list(configDir)) {
            files = stream
                    .filter(p -> p.getFileName().toString().matches("node\\d+\\.json"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.List<Path> withoutIndex = new ArrayList<>();
        for (Path p : files) {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(p.toFile());
            if (root.hasNonNull("party_index")) {
                byParty.put(root.get("party_index").asInt(), p);
            } else {
                withoutIndex.add(p);
            }
        }
        for (Path p : withoutIndex) {
            int num = Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""));
            int party = Math.max(0, num - 1);
            log.warn("node config {} 无 party_index 字段，按历史约定推断为 party {}（请修正配置）",
                    p.getFileName(), party);
            byParty.putIfAbsent(party, p);
        }
        if (byParty.size() < 3) {
            throw new IllegalStateException("expected >=3 node configs under " + configDir
                    + ", found " + byParty.size() + " (keys=" + byParty.keySet() + "); "
                    + "run scripts/gen-mpc-engine-configs.sh --layout native or "
                    + "mpc-engine/scripts/start-mpc-cluster.sh --setup-only");
        }
        log.info("node configs discovered by party_index: {}", byParty);
        return byParty;
    }

    /** 取某参与方的配置（{@link #nodeConfigs} 必须已由 {@code @BeforeAll} 初始化）。 */
    private static Path resolveNodeConfig(int partyIndex) {
        Path p = nodeConfigs.get(partyIndex);
        if (p == null) {
            throw new IllegalStateException("no node config for party " + partyIndex
                    + " (discovered: " + nodeConfigs.keySet() + ")");
        }
        return p;
    }

    /**
     * 从各方 {@code nodeN.json} 的 listen_addr 解析引擎端口（与配置集同源，
     * 不再硬编码——见 {@link #ports} 字段注释）。
     */
    private static int[] resolveEnginePorts(Path configDir) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        int[] resolved = new int[3];
        for (int i = 0; i < 3; i++) {
            Path cfg = resolveNodeConfig(i);
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(cfg.toFile());
            String listen = root.path("listen_addr").asText("");
            int colon = listen.lastIndexOf(':');
            if (colon < 0 || colon == listen.length() - 1) {
                throw new IllegalStateException(
                        "invalid listen_addr in " + cfg + ": '" + listen + "'");
            }
            resolved[i] = Integer.parseInt(listen.substring(colon + 1).trim());
        }
        log.info("engine ports resolved from configs: {}", java.util.Arrays.toString(resolved));
        return resolved;
    }

    /** 等待某端口可连（引擎就绪探测）；超时抛 {@link IllegalStateException}。 */
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
