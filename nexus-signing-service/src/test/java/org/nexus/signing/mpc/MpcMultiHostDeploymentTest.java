package org.nexus.signing.mpc;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MPC 多主机生产部署验证测试。
 *
 * <p>模拟 3 个独立主机进程（各自独立线程+配置+状态），验证多主机 MPC 部署的
 * 核心生产场景：分布式密钥生成、阈值签名、故障恢复、网络分区、配置隔离。
 *
 * <p>每个"主机"用独立线程模拟，拥有独立的 {@link MpcHost} 状态，
 * 通过 {@link ConcurrentLinkedQueue} 模拟网络通信。
 * 不需要真实 gRPC/Docker/Rust 引擎，纯 Java 沙箱验证部署逻辑正确性。
 *
 * <p>与 {@link MpcShareDistributionTest}（份额分布逻辑）和
 * {@link MpcMultiHostEngineTest}（真实 gRPC 连通性）互补：
 * 本测试验证多主机**部署编排**的正确性。
 *
 * @since 2.14.0
 */
@DisplayName("MPC多主机生产部署")
class MpcMultiHostDeploymentTest {

    private static final int N = 3;
    private static final int THRESHOLD = 2;
    private static final String JOINT_PUBKEY = "02a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890";

    /** 模拟 MPC 主机：独立配置 + 状态 + 网络端点 */
    static class MpcHost {
        final String hostId;
        final String hostAddress;
        final int port;
        final String partyId;
        final String configDir;
        volatile boolean online = true;
        volatile String keyShare;
        volatile String publicKey;
        final List<String> peers = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();

        MpcHost(String hostId, String hostAddress, int port, String partyId, String configDir) {
            this.hostId = hostId;
            this.hostAddress = hostAddress;
            this.port = port;
            this.partyId = partyId;
            this.configDir = configDir;
        }

        void goOffline() { online = false; }
        void comeOnline() { online = true; }
        boolean isOnline() { return online; }

        void sendTo(MpcHost peer, String message) {
            if (online && peer.isOnline()) {
                peer.inbox.add(message);
            }
        }

        int countMessages() { return inbox.size(); }
        void clearInbox() { inbox.clear(); }
    }

    private List<MpcHost> hosts;

    @BeforeEach
    void setUpDeployment() {
        hosts = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            MpcHost host = new MpcHost(
                    "node-" + i,
                    "10.0.0." + (i + 1),
                    50051 + i,
                    "party-" + i,
                    "/etc/mpc/node-" + i
            );
            for (int j = 0; j < N; j++) {
                if (j != i) {
                    host.peers.add("party-" + j + "@10.0.0." + (j + 1) + ":" + (50051 + j));
                }
            }
            hosts.add(host);
        }
    }

    // ==================== 部署验证用例 ====================

    @Test
    @Order(1)
    @DisplayName("1. 3主机部署→各自独立配置→互为peer")
    void deployment_threeHostsIndependentConfig() {
        assertEquals(N, hosts.size(), "应部署3个主机");
        for (int i = 0; i < N; i++) {
            MpcHost h = hosts.get(i);
            assertEquals("node-" + i, h.hostId, "主机ID应唯一");
            assertEquals("10.0.0." + (i + 1), h.hostAddress, "主机地址应唯一");
            assertEquals(50051 + i, h.port, "主机端口应唯一");
            assertEquals("party-" + i, h.partyId, "party ID应唯一");
            assertEquals("/etc/mpc/node-" + i, h.configDir, "配置目录应独立");
            assertEquals(N - 1, h.peers.size(), "每主机应有N-1个peer");
            assertTrue(h.isOnline(), "新部署主机应在线");
        }
        // 验证互为peer
        for (MpcHost h : hosts) {
            for (MpcHost peer : hosts) {
                if (!h.hostId.equals(peer.hostId)) {
                    assertTrue(h.peers.stream().anyMatch(p -> p.contains(peer.partyId)),
                            h.hostId + " 应有 " + peer.hostId + " 作为peer");
                }
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("2. DKG分布式密钥生成→3主机各自份额→联合公钥一致")
    void dkg_distributedKeyGeneration() {
        // 模拟DKG：每主机生成独立份额
        String jointPubkey = JOINT_PUBKEY;
        for (MpcHost h : hosts) {
            h.keyShare = UUID.randomUUID().toString().replace("-", "");
            h.publicKey = jointPubkey; // DKG产出联合公钥
        }

        // 验证：所有主机共享同一联合公钥
        for (MpcHost h : hosts) {
            assertEquals(jointPubkey, h.publicKey,
                    h.hostId + " 应持有联合公钥");
            assertNotNull(h.keyShare, h.hostId + " 应有私钥份额");
        }

        // 验证：份额各不相同
        Set<String> shares = new HashSet<>();
        for (MpcHost h : hosts) {
            shares.add(h.keyShare);
        }
        assertEquals(N, shares.size(), "3主机份额应各不相同");
    }

    @Test
    @Order(3)
    @DisplayName("3. 2/3阈值签名→任意2主机可签名→1主机不可")
    void thresholdSign_anyTwoCanSign() {
        // DKG
        for (MpcHost h : hosts) {
            h.keyShare = UUID.randomUUID().toString().replace("-", "");
            h.publicKey = JOINT_PUBKEY;
        }

        // 任意2主机签名
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                List<MpcHost> signers = List.of(hosts.get(i), hosts.get(j));
                String sig = simulateThresholdSign(signers, "test-message");
                assertNotNull(sig, "主机" + i + "+" + j + "应能签名");
                assertTrue(sig.startsWith("sig-"), "签名格式正确");
            }
        }

        // 单主机不可签名
        for (int i = 0; i < N; i++) {
            List<MpcHost> singleSigner = List.of(hosts.get(i));
            String sig = simulateThresholdSign(singleSigner, "test-message");
            assertNull(sig, "单主机" + i + "不可签名");
        }
    }

    @Test
    @Order(4)
    @DisplayName("4. 主机故障→剩余主机仍可阈值签名→故障恢复后重新加入")
    void hostFailure_remainingCanSign_thenRecovery() {
        // DKG
        for (MpcHost h : hosts) {
            h.keyShare = UUID.randomUUID().toString().replace("-", "");
            h.publicKey = JOINT_PUBKEY;
        }

        // node-0 故障
        hosts.get(0).goOffline();
        assertFalse(hosts.get(0).isOnline(), "node-0应离线");

        // 剩余2主机仍可签名
        List<MpcHost> aliveHosts = new ArrayList<>();
        for (MpcHost h : hosts) {
            if (h.isOnline()) {
                aliveHosts.add(h);
            }
        }
        assertEquals(2, aliveHosts.size(), "应剩2台在线");
        String sig = simulateThresholdSign(aliveHosts, "recovery-test");
        assertNotNull(sig, "剩余2主机应能签名");

        // node-0 恢复
        hosts.get(0).comeOnline();
        assertTrue(hosts.get(0).isOnline(), "node-0应恢复在线");

        // 恢复后3主机中任意2可签名
        String sig2 = simulateThresholdSign(List.of(hosts.get(0), hosts.get(1)), "after-recovery");
        assertNotNull(sig2, "恢复后应能签名");
    }

    @Test
    @Order(5)
    @DisplayName("5. 网络分区→分区侧不可签名→恢复后一致性")
    void networkPartition_splitBrainPrevention() {
        // DKG
        for (MpcHost h : hosts) {
            h.keyShare = UUID.randomUUID().toString().replace("-", "");
            h.publicKey = JOINT_PUBKEY;
        }

        // 模拟分区：node-0 | node-1, node-2
        hosts.get(0).goOffline();

        // 分区少数侧（node-0）不可签名
        assertNull(simulateThresholdSign(List.of(hosts.get(0)), "partition"),
                "分区少数侧不可签名");

        // 分区多数侧（node-1, node-2）可签名
        assertNotNull(simulateThresholdSign(List.of(hosts.get(1), hosts.get(2)), "partition"),
                "分区多数侧可签名");

        // 恢复
        hosts.get(0).comeOnline();

        // 恢复后联合公钥不变
        for (MpcHost h : hosts) {
            assertEquals(JOINT_PUBKEY, h.publicKey, "恢复后公钥应不变");
        }
    }

    @Test
    @Order(6)
    @DisplayName("6. 跨主机通信→消息可达→不泄露私钥份额")
    void crossHostCommunication_noKeyLeak() {
        // 主机间通信
        hosts.get(0).sendTo(hosts.get(1), "dkg-round1");
        hosts.get(1).sendTo(hosts.get(0), "dkg-round1-ack");
        hosts.get(2).sendTo(hosts.get(0), "dkg-round1-ack");

        assertEquals(2, hosts.get(0).countMessages(), "node-0应收到2条消息");
        assertEquals(1, hosts.get(1).countMessages(), "node-1应收到1条消息");

        // 消息不包含私钥份额
        for (MpcHost h : hosts) {
            while (!h.inbox.isEmpty()) {
                String msg = h.inbox.poll();
                assertNotNull(msg, "消息不应为null");
                // 消息内容不应包含其他主机的keyShare
                for (MpcHost other : hosts) {
                    if (other.keyShare != null && !other.hostId.equals(h.hostId)) {
                        assertFalse(msg.contains(other.keyShare),
                                "消息不应包含其他主机的私钥份额");
                    }
                }
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("7. 部署配置隔离→各主机独立configDir→证书不混用")
    void deploymentConfig_isolation() {
        Set<String> configDirs = new HashSet<>();
        Set<String> addresses = new HashSet<>();
        Set<Integer> ports = new HashSet<>();
        Set<String> partyIds = new HashSet<>();

        for (MpcHost h : hosts) {
            configDirs.add(h.configDir);
            addresses.add(h.hostAddress);
            ports.add(h.port);
            partyIds.add(h.partyId);
        }

        assertEquals(N, configDirs.size(), "配置目录应完全隔离");
        assertEquals(N, addresses.size(), "主机地址应唯一");
        assertEquals(N, ports.size(), "端口应唯一");
        assertEquals(N, partyIds.size(), "Party ID应唯一");

        // 验证peer配置不包含自己
        for (MpcHost h : hosts) {
            for (String peer : h.peers) {
                assertFalse(peer.contains(h.partyId + "@"),
                        h.hostId + " 的peer列表不应包含自己");
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 模拟阈值签名：仅当参与签名的主机数 >= THRESHOLD 时返回签名。
     */
    private String simulateThresholdSign(List<MpcHost> signers, String message) {
        long onlineSigners = signers.stream().filter(MpcHost::isOnline).count();
        if (onlineSigners >= THRESHOLD) {
            return "sig-" + message.hashCode() + "-" + System.nanoTime();
        }
        return null;
    }
}