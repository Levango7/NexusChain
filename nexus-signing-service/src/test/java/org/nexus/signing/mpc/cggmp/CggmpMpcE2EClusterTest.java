package org.nexus.signing.mpc.cggmp;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CggmpMpcE2EClusterTest {

    private static final Logger log = LoggerFactory.getLogger(CggmpMpcE2EClusterTest.class);

    private static final Path DEFAULT_BIN = Paths.get(
            "F:", "Nexus", "NexusChain", "mpc-engine", "target", "debug", "mpc-engine.exe");
    private static final Path CONFIG_DIR = Paths.get(
            "F:", "Nexus", "NexusChain", "mpc-engine", "config");
    private static final Path LOG_DIR = Paths.get(
            "F:", "Nexus", "NexusChain", "mpc-engine", "logs");
    private static final long DEADLINE_MS = 60_000L;
    private static final int MAX_ROUNDS = 200;

    private static Path engineBinary;
    private static final List<Process> engines = new ArrayList<>();
    private static final List<ManagedChannel> channels = new ArrayList<>();
    private static final List<MpcCggmpClient> partyClients = new ArrayList<>();
    private static MpcCggmpClient coordinatorClient;

    /** keygen/aux 阶段 pump 状态 */
    private static class PumpState {
        final List<CgRelayMessageDto> outgoing;
        final boolean finished;
        final String aggregatePublicKey;

        PumpState(List<CgRelayMessageDto> outgoing, boolean finished, String aggregatePublicKey) {
            this.outgoing = outgoing == null ? Collections.emptyList() : outgoing;
            this.finished = finished;
            this.aggregatePublicKey = aggregatePublicKey;
        }
    }

    /** sign 阶段 pump 状态（仅签名方） */
    private static class SignPumpState {
        final List<CgRelayMessageDto> outgoing;
        final boolean finished;
        final String rHex;
        final String sHex;
        final int signerIdx;

        SignPumpState(List<CgRelayMessageDto> outgoing, boolean finished,
                      String rHex, String sHex, int signerIdx) {
            this.outgoing = outgoing == null ? Collections.emptyList() : outgoing;
            this.finished = finished;
            this.rHex = rHex;
            this.sHex = sHex;
            this.signerIdx = signerIdx;
        }
    }

    @BeforeAll
    static void startCluster() throws Exception {
        String envBin = System.getenv("MPC_ENGINE_BIN");
        engineBinary = envBin != null ? Paths.get(envBin) : DEFAULT_BIN;
        if (!Files.isRegularFile(engineBinary)) {
            throw new IllegalStateException("mpc-engine binary not found: " + engineBinary.toAbsolutePath());
        }
        Files.createDirectories(LOG_DIR);

        for (int i = 1; i <= 3; i++) {
            Path configPath = CONFIG_DIR.resolve("node" + i + ".json");
            if (!Files.isRegularFile(configPath)) {
                throw new IllegalStateException("node config missing: " + configPath);
            }
            ProcessBuilder pb = new ProcessBuilder(
                    engineBinary.toString(), "--config", configPath.toString())
                    .redirectErrorStream(true)
                    .directory(engineBinary.getParent().toFile());
            pb.environment().put("MPC_CONFIG_PATH", configPath.toString());
            pb.environment().put("MPC_ENGINE_SESSION_DIR",
                    Paths.get("F:", "Nexus", "NexusChain", "mpc-engine",
                            "data", "node" + i, "sessions").toString());
            pb.environment().put("MPC_REQUIRE_TLS", "true");
            pb.environment().put("MPC_AUTH_TOKEN", "nexus-mpc-test-token");
            pb.environment().put("RUST_LOG", "info");
            Process p = pb.start();
            engines.add(p);
            drainStdout(p, "node" + i);
        }

        waitForPort(50051, 15_000);
        waitForPort(50052, 5_000);
        waitForPort(50053, 5_000);
        log.info("3 mpc-engine nodes up: 127.0.0.1:50051,50052,50053");

        Path certDir = engineBinary.getParent().getParent().getParent().resolve("certs");
        String trustCertPath = certDir.resolve("ca.crt").toString();
        int[] ports = {50051, 50052, 50053};
        for (int i = 0; i < ports.length; i++) {
            int port = ports[i];
            String nodeName = "node" + (i + 1);
            String clientCertPath = certDir.resolve(nodeName + ".crt").toString();
            String clientKeyPath = certDir.resolve(nodeName + ".key").toString();
            io.grpc.netty.shaded.io.netty.handler.ssl.SslContext clientSsl =
                    org.nexus.signing.mpc.transport.GrpcTlsContextFactory.buildClientSslContext(
                            trustCertPath, clientCertPath, clientKeyPath);
            ManagedChannel ch = NettyChannelBuilder
                    .forAddress("127.0.0.1", port)
                    .overrideAuthority("localhost")
                    .sslContext(clientSsl)
                    .build();
            channels.add(ch);
            MpcCggmpClient client = new MpcCggmpClient(
                    MpcCryptoServiceGrpc.newBlockingStub(ch).withInterceptors(
                            new io.grpc.ClientInterceptor() {
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
            partyClients.add(client);
        }
        coordinatorClient = partyClients.get(0);
        log.info("Java clients ready (3 mTLS + Bearer auth channels)");
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

    // ============================================================
    // J 批：完整流水线
    // ============================================================

    @Test
    @DisplayName("J 批：完整流水线 2-of-3 签名+验签+篡改拒绝（复刻 F 批串行轮转）")
    void cggmpFullLifecycleE2E() throws Exception {
        String sid = "j-batch-full-" + System.currentTimeMillis();
        int n = 3;
        int t = 2;
        byte[] messageHash = sha256(("J-batch-full:" + sid).getBytes(StandardCharsets.UTF_8));

        // ---- Phase 1: keygen ----
        log.info("=== Phase 1: CGGMP21 keygen(n={}, t={}) session={} ===", n, t, sid);
        // Step 1a: StartKeygen ×3（不跑 pump——直接调 client.startKeygen，复刻 F 批行为）
        List<PumpState> states = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CgPumpResult r = partyClients.get(i).startKeygen(sid, 0, i, n, t);
            assertTrue(r.isSuccess(), "start keygen " + i + " failed: " + r.getError());
            states.add(new PumpState(r.getOutgoing(), r.isFinished(), r.getAggregatePublicKey()));
        }
        // Step 1b: 协议轮转（复刻 F 批 run_protocol_over_relay）
        states = runProtocolOverRelay(states, sid, true, n);
        String aggPk = states.get(0).aggregatePublicKey;
        assertNotNull(aggPk);
        for (int i = 1; i < n; i++) {
            assertEquals(aggPk, states.get(i).aggregatePublicKey,
                    "party " + i + " agg pubkey mismatch");
        }
        log.info("keygen done, agg pubkey = {}", aggPk);

        // ---- Phase 2: aux ----
        log.info("=== Phase 2: CGGMP21 aux ===");
        states = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CgPumpResult r = partyClients.get(i).startAux(sid, 0, i, n);
            assertTrue(r.isSuccess(), "start aux " + i + " failed: " + r.getError());
            states.add(new PumpState(r.getOutgoing(), r.isFinished(), null));
        }
        states = runProtocolOverRelay(states, sid, false, n);
        assertTrue(states.stream().allMatch(s -> s.finished), "aux must finish");
        log.info("aux done");

        // ---- Phase 3: assembleShare ----
        log.info("=== Phase 3: assembleShare (每方) ===");
        for (int i = 0; i < n; i++) {
            boolean ok = partyClients.get(i).assembleShare(sid);
            assertTrue(ok, "assembleShare party " + i + " failed");
        }
        log.info("assembleShare done");

        // ---- Phase 4: sign (2-of-3: signers = [0, 1]) ----
        log.info("=== Phase 4: CGGMP21 sign(t=2) ===");
        int[] signers = new int[]{0, 1};
        List<SignPumpState> signStates = new ArrayList<>();
        for (int batchPos = 0; batchPos < 2; batchPos++) {
            int signerIdx = signers[batchPos];
            CgSignPumpResult r = partyClients.get(signerIdx).startSign(
                    sid, 0, batchPos, signers, messageHash);
            assertTrue(r.isSuccess(), "start sign " + signerIdx + " failed: " + r.getError());
            signStates.add(new SignPumpState(r.getOutgoing(), r.isFinished(),
                    r.getRHex(), r.getSHex(), signerIdx));
        }
        int round = 0;
        while (true) {
            if (signStates.stream().allMatch(s -> s.finished)) break;
            round++;
            assertTrue(round < MAX_ROUNDS, "sign stuck at round " + round);
            for (SignPumpState st : signStates) {
                if (st.finished) continue;
                for (CgRelayMessageDto m : st.outgoing) {
                    boolean ok = coordinatorClient.publishRelay(m);
                    assertTrue(ok, "publish failed for sender " + m.getSenderIndex());
                }
            }
            List<SignPumpState> next = new ArrayList<>();
            for (SignPumpState st : signStates) {
                if (st.finished) { next.add(st); continue; }
                List<CgRelayMessageDto> incoming = coordinatorClient.pullRelay(sid, st.signerIdx);
                if (incoming == null) incoming = Collections.emptyList();
                CgSignPumpResult r = partyClients.get(st.signerIdx).pumpSign(sid, incoming);
                assertTrue(r.isSuccess(), "sign pump error: " + r.getError());
                if (r.isFinished()) {
                    assertNotNull(r.getRHex());
                    assertNotNull(r.getSHex());
                    next.add(new SignPumpState(Collections.emptyList(), true,
                            r.getRHex(), r.getSHex(), st.signerIdx));
                } else {
                    next.add(new SignPumpState(r.getOutgoing(), false, null, null, st.signerIdx));
                }
            }
            signStates = next;
        }
        String rHex = signStates.get(0).rHex;
        String sHex = signStates.get(0).sHex;
        for (int i = 1; i < 2; i++) {
            assertEquals(rHex, signStates.get(i).rHex, "r mismatch between signers");
            assertEquals(sHex, signStates.get(i).sHex, "s mismatch between signers");
        }
        log.info("sign done: r={}... s={}...", rHex.substring(0, 8), sHex.substring(0, 8));

        // ---- Phase 5: verify ----
        log.info("=== Phase 5: verify ===");
        CgVerifyResult v = partyClients.get(0).verifySignature(
                sid, hexToBytes(rHex), hexToBytes(sHex), messageHash);
        assertTrue(v.isSuccess(), "verify RPC failed: " + v.getError());
        assertTrue(v.isValid(), "signature must verify valid");
        byte[] badR = hexToBytes(rHex);
        badR[0] ^= (byte) 0xFF;
        CgVerifyResult inv = partyClients.get(0).verifySignature(
                sid, badR, hexToBytes(sHex), messageHash);
        assertTrue(inv.isSuccess(), "verify RPC failed on tampered");
        assertFalse(inv.isValid(), "tampered signature must be invalid");
        log.info("verify passed, tampered rejected");

        // ---- 最终 CgStatus 快照 ----
        CgStatus st = partyClients.get(0).status(sid);
        assertTrue(st.isHasKeyShare(), "status should have key_share after full lifecycle");
        log.info("J 批完整流水线验证通过：keygen→aux→assemble→sign→verify 全绿");
    }

    /** 复刻 F 批 run_protocol_over_relay：串行轮转 publish→pull→pump */
    private List<PumpState> runProtocolOverRelay(
            List<PumpState> states, String sid, boolean isKeygen, int n) {
        int round = 0;
        while (true) {
            if (states.stream().allMatch(s -> s.finished)) return states;
            round++;
            assertTrue(round < MAX_ROUNDS, "protocol stuck at round " + round);
            for (PumpState st : states) {
                if (st.finished) continue;
                for (CgRelayMessageDto m : st.outgoing) {
                    boolean ok = coordinatorClient.publishRelay(m);
                    assertTrue(ok, "publish failed for sender " + m.getSenderIndex());
                }
            }
            List<PumpState> next = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                PumpState st = states.get(i);
                if (st.finished) { next.add(st); continue; }
                List<CgRelayMessageDto> incoming = coordinatorClient.pullRelay(sid, i);
                if (incoming == null) incoming = Collections.emptyList();
                CgPumpResult r = isKeygen
                        ? partyClients.get(i).pumpKeygen(sid, incoming)
                        : partyClients.get(i).pumpAux(sid, incoming);
                assertTrue(r.isSuccess(), "pump " + (isKeygen ? "keygen" : "aux")
                        + " party " + i + " error: " + r.getError());
                next.add(new PumpState(r.getOutgoing(), r.isFinished(),
                        isKeygen ? r.getAggregatePublicKey() : null));
            }
            states = next;
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    private static byte[] sha256(byte[] input) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(input); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return new byte[0];
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
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                Path logPath = LOG_DIR.resolve("j-batch-" + tag + ".log");
                try (var fout = Files.newOutputStream(logPath)) {
                    int n;
                    while ((n = in.read(buf)) > 0) fout.write(buf, 0, n);
                }
            } catch (IOException e) { log.warn("drain stdout for {} failed: {}", tag, e.getMessage()); }
        }, "drain-" + tag);
        t.setDaemon(true);
        t.start();
    }
}
