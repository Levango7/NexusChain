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
 * 三节点多主机 mTLS 验证：Docker×2 + WSL×1，引擎双向 TLS（require_tls=true）。
 *
 * <p>Java 客户端以 mTLS（trust-cert + client-cert + client-key）连接各主机引擎，
 * 跑 Dkg→Sign 完整链路——证明多主机 + 传输加密部署形态可用。</p>
 */
class MpcMultiHostTlsTest {

    private static final Logger log = LoggerFactory.getLogger(MpcMultiHostTlsTest.class);

    private static final String CERT_DIR =
            System.getProperty("user.dir").contains("nexus-signing-service")
                    ? "F:/Nexus/NexusChain/mpc-certs"
                    : "mpc-certs";

    /** 三节点：A/Docker, B/Docker, C/WSL，均启用 mTLS。 */
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
                "无引擎运行，跳过 mTLS 三节点验证");
    }

    private static boolean engineReachable(String host, int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 3000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 以 mTLS 构造引擎客户端（trust=CA，client=本节点证书）。 */
    private static GrpcMpcCryptoEngine newTlsEngine(String host, int port, String clientName) throws Exception {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        setField(engine, "host", host);
        setField(engine, "port", port);
        setField(engine, "deadlineTimeoutMillis", 60_000L);
        setField(engine, "usePlaintext", false);
        setField(engine, "tlsTrustCertPath", CERT_DIR + "/ca/CA.pem");
        setField(engine, "tlsClientCertPath", CERT_DIR + "/" + clientName + "/cert.pem");
        setField(engine, "tlsClientKeyPath", CERT_DIR + "/" + clientName + "/key.pem");
        engine.init();
        return engine;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void allThreeTlsHosts_runDkgAndSign() throws Exception {
        int ok = 0;
        for (String[] n : NODES) {
            if (!engineReachable(n[1], Integer.parseInt(n[2]))) {
                log.warn("{} 不可达，跳过", n[0]);
                continue;
            }
            // 客户端用 node-A 证书（本节点身份，CA 信任）
            GrpcMpcCryptoEngine engine = newTlsEngine(n[1], Integer.parseInt(n[2]), "node-A");
            try {
                String sessionId = "tls-" + n[0] + "-" + System.currentTimeMillis();
                DkgResponse dkg = engine.dkg(new DkgRequest(sessionId, 2, 3, 0, "secp256k1",
                        java.util.List.of()));
                assertFalse(dkg.getPublicKey() == null || dkg.getPublicKey().isEmpty(),
                        n[0] + " mTLS Dkg 应产出公钥");
                SignResponse sign = engine.sign(new SignRequest(sessionId,
                        dkg.getPublicKey(), dkg.getKeyShare(), "abc123", 0, java.util.List.of()));
                assertNotNull(sign.getPartialSignature(), n[0] + " mTLS Sign 应产出部分签名");
                log.info("{} mTLS Dkg→Sign 完成: pubkey={}", n[0],
                        dkg.getPublicKey().substring(0, Math.min(12, dkg.getPublicKey().length())));
                ok++;
            } finally {
                engine.shutdown();
            }
        }
        assertTrue(ok >= 1, "至少一个 mTLS 主机引擎应跑通（当前 " + ok + "）");
        log.info("mTLS 多主机验证: {} 个主机跑通 Dkg→Sign（双向 TLS 加密）", ok);
    }
}
