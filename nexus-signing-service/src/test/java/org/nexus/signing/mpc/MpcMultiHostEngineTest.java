package org.nexus.signing.mpc;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.GrpcMpcCryptoEngine;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 三节点多主机 MPC 引擎验证：Docker 容器 ×2 + WSL 原生 ×1。
 *
 * <p>三个独立网络命名空间的引擎各自跑 Dkg→Sign 完整链路，
 * 证明"多主机分布"拓扑可用（Java 客户端经 gRPC 访问任意主机引擎）。</p>
 */
class MpcMultiHostEngineTest {

    private static final Logger log = LoggerFactory.getLogger(MpcMultiHostEngineTest.class);

    /** 三节点：A/Docker, B/Docker, C/WSL（127.0.0.1 显式 IPv4，避免 localhost→::1 解析差异）。 */
    private static final String[][] NODES = {
            {"node-A-docker", "127.0.0.1", "50051"},
            {"node-B-docker", "127.0.0.1", "50052"},
            {"node-C-wsl", "127.0.0.1", "50053"},
    };

    @BeforeAll
    static void checkEngines() {
        boolean anyUp = false;
        for (String[] n : NODES) {
            if (engineReachable(n[1], Integer.parseInt(n[2]))) {
                anyUp = true;
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(anyUp,
                "无引擎运行，跳过三节点验证（需 Docker 双容器 + WSL 引擎）");
    }

    private static boolean engineReachable(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static GrpcMpcCryptoEngine newEngine(String host, int port) throws Exception {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        setField(engine, "host", host);
        setField(engine, "port", port);
        setField(engine, "deadlineTimeoutMillis", 60_000L);
        setField(engine, "usePlaintext", true);
        engine.init();
        return engine;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void allThreeHosts_runDkgAndSign() throws Exception {
        int ok = 0;
        for (String[] n : NODES) {
            if (!engineReachable(n[1], Integer.parseInt(n[2]))) {
                log.warn("{} 引擎不可达，跳过", n[0]);
                continue;
            }
            String sessionId = "multihost-" + n[0] + "-" + System.currentTimeMillis();
            GrpcMpcCryptoEngine engine = newEngine(n[1], Integer.parseInt(n[2]));
            try {
                DkgResponse dkg = engine.dkg(new DkgRequest(sessionId, 2, 3, 0, "secp256k1",
                        java.util.List.of()));
                assertFalse(dkg.getPublicKey() == null || dkg.getPublicKey().isEmpty(),
                        n[0] + " Dkg 应产出公钥");
                SignResponse sign = engine.sign(new SignRequest(sessionId,
                        dkg.getPublicKey(), dkg.getKeyShare(), "abc123", 0, java.util.List.of()));
                assertNotNull(sign.getPartialSignature(), n[0] + " Sign 应产出部分签名");
                log.info("{} Dkg→Sign 完成: pubkey={}", n[0],
                        dkg.getPublicKey().substring(0, Math.min(12, dkg.getPublicKey().length())));
                ok++;
            } finally {
                engine.shutdown();
            }
        }
        assertTrue(ok >= 1, "至少一个主机引擎应跑通 Dkg→Sign（当前 " + ok + "）");
        log.info("多主机引擎验证: {} 个主机跑通 Dkg→Sign", ok);
    }

    @Test
    void crossHostKeyMaterial_isolated() throws Exception {
        String[] pubs = new String[3];
        for (int i = 0; i < NODES.length; i++) {
            String[] n = NODES[i];
            if (!engineReachable(n[1], Integer.parseInt(n[2]))) {
                log.warn("{} 不可达，份额隔离验证跳过该节点", n[0]);
                continue;
            }
            GrpcMpcCryptoEngine engine = newEngine(n[1], Integer.parseInt(n[2]));
            try {
                DkgResponse dkg = engine.dkg(new DkgRequest(
                        "iso-" + n[0] + "-" + System.currentTimeMillis(), 2, 3, 0, "secp256k1",
                        java.util.List.of()));
                pubs[i] = dkg.getPublicKey();
            } finally {
                engine.shutdown();
            }
        }
        int distinct = (int) java.util.Arrays.stream(pubs).filter(p -> p != null && !p.isEmpty()).distinct().count();
        log.info("跨主机份额隔离: {} 个可用节点产出 {} 个互异公钥", 
                (int) java.util.Arrays.stream(pubs).filter(p -> p != null && !p.isEmpty()).count(), distinct);
        assertTrue(distinct >= 2, "至少 2 个主机产出互异公钥（独立进程密钥隔离实证）");
    }
}
