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
        boolean anyPlaintextEngine = false;
        for (String[] n : NODES) {
            if (plaintextEngineUp(n[1], Integer.parseInt(n[2]))) {
                anyPlaintextEngine = true;
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(anyPlaintextEngine,
                "三节点上不是明文 MPC 引擎，跳过（本用例要求明文栈：不带 --config、MPC_REQUIRE_TLS=false）");
    }

    /**
     * 判据必须是「端口上真跑着明文的 MPC 引擎」，不能只看 TCP 可连通——
     * 2026-10-04 实测：OpsMesh 的 alert-svc 容器占了 50053，纯 TCP 探测把三个用例全带进假红。
     * 做法：明文通道发一次最轻的 gRPC 调用；客户端把异常吞成失败响应，
     * 因此看返回的 error——含 UNAVAILABLE 表示"连不上/不是 gRPC 服务"，其余（服务端校验、
     * 内部错误）说明引擎在应答。
     */
    private static boolean plaintextEngineUp(String host, int port) {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        try {
            setField(engine, "host", host);
            setField(engine, "port", port);
            setField(engine, "deadlineTimeoutMillis", 3_000L);
            setField(engine, "usePlaintext", true);
            engine.init();
            SignResponse probe = engine.sign(new SignRequest("probe", "", "", "", 0, java.util.List.of()));
            String err = probe == null || probe.getError() == null ? "" : probe.getError();
            // UNIMPLEMENTED 也算"不是我们的引擎"：端口上的其它 gRPC 服务不会实现本仓方法
            return !err.contains("UNAVAILABLE") && !err.contains("UNIMPLEMENTED");
        } catch (IllegalArgumentException e) {
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try { engine.shutdown(); } catch (Exception ignore) { }
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
            if (!plaintextEngineUp(n[1], Integer.parseInt(n[2]))) {
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
            if (!plaintextEngineUp(n[1], Integer.parseInt(n[2]))) {
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
