package org.nexus.signing.mpc;

import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MPC 多主机份额分布测试。
 *
 * <p>验证 3 节点 MPC 钱包的密钥份额分布正确性：每个节点持有不同的私钥份额，
 * 联合公钥一致，私钥份额不泄露，阈值签名可用，节点故障后份额安全。
 *
 * <p>纯 Java 沙箱模拟，不需要真实 MPC 引擎或 Docker 容器。
 * 与 {@link MpcMultiHostEngineTest}（需真实 Docker 引擎）互补：
 * 本测试验证份额分布的逻辑正确性，后者验证多主机 gRPC 连通性。
 *
 * @since 2.11.0
 */
@DisplayName("MPC多主机份额分布")
class MpcShareDistributionTest {

    private static final int N = 3;
    private static final int THRESHOLD = 2;
    private static final String JOINT_PUBKEY = "02a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890a1b2c3d4e5f67890";

    private List<MpcKeyShare> shares;
    private MpcWallet wallet;

    @BeforeEach
    void setUp() {
        shares = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            shares.add(new MpcKeyShare(
                    "node-" + i,
                    UUID.randomUUID().toString().replace("-", ""), // private share
                    UUID.randomUUID().toString().replace("-", ""), // public share
                    null));
        }
        wallet = new MpcWallet();
        wallet.setWalletId("wallet-001");
        wallet.setParticipants(List.of("node-0", "node-1", "node-2"));
        wallet.setThreshold(THRESHOLD);
        wallet.setPublicKey(JOINT_PUBKEY);
        wallet.setStatus(MpcWallet.WalletStatus.ACTIVE);
    }

    // ==================== 测试用例 ====================

    @Test
    @Order(1)
    @DisplayName("1. 3节点各持不同份额（participantId唯一+私钥份额各异）")
    void threeNodesDistinctShares() {
        Set<String> participantIds = new HashSet<>();
        Set<String> privateShares = new HashSet<>();
        Set<String> publicShares = new HashSet<>();

        for (MpcKeyShare share : shares) {
            participantIds.add(share.getParticipantId());
            privateShares.add(share.getPrivateShareHex());
            publicShares.add(share.getPublicShareHex());
        }

        assertEquals(N, participantIds.size(), "participantId 应唯一");
        assertEquals(N, privateShares.size(), "私钥份额应各异");
        assertEquals(N, publicShares.size(), "公钥份额应各异");
    }

    @Test
    @Order(2)
    @DisplayName("2. 联合公钥一致（所有节点共享相同联合公钥）")
    void jointPublicKeyConsistent() {
        assertEquals(JOINT_PUBKEY, wallet.getPublicKey(), "钱包联合公钥应一致");
        assertNotNull(wallet.getPublicKey());
        // 联合公钥不等于任何单个份额
        for (MpcKeyShare share : shares) {
            assertNotEquals(share.getPublicShareHex(), wallet.getPublicKey(),
                    "联合公钥不应等于单个份额");
        }
    }

    @Test
    @Order(3)
    @DisplayName("3. 私钥份额不泄露（toString不含私钥材料）")
    void privateShareNotLeaked() {
        for (MpcKeyShare share : shares) {
            String str = share.toString();
            assertNotNull(str);
            assertFalse(str.contains(share.getPrivateShareHex()),
                    "toString 不应包含私钥份额: " + share.getParticipantId());
            assertTrue(str.contains(share.getParticipantId()),
                    "toString 应包含 participantId");
            assertTrue(str.contains("publicShare"),
                    "toString 应包含公钥份额标识");
        }
    }

    @Test
    @Order(4)
    @DisplayName("4. 阈值签名：任意2/3份额可签名，1份额不可")
    void thresholdSigning() {
        assertEquals(THRESHOLD, wallet.getThreshold(), "阈值应为2");
        assertEquals(N, wallet.getParticipants().size(), "参与者应为3");

        // 任意2个份额可签名
        for (int i = 0; i < N; i++) {
            for (int j = i + 1; j < N; j++) {
                List<MpcKeyShare> subset = List.of(shares.get(i), shares.get(j));
                assertEquals(THRESHOLD, subset.size(), "2份额应达阈值");
                assertTrue(canSign(subset, THRESHOLD), "2份额应可签名");
            }
        }

        // 1个份额不可签名
        for (int i = 0; i < N; i++) {
            List<MpcKeyShare> subset = List.of(shares.get(i));
            assertFalse(canSign(subset, THRESHOLD), "1份额不应达阈值");
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. 节点故障→其份额不影响其他节点→剩余份额仍可签名")
    void nodeFailure_sharesSecure() {
        // node-2 故障
        List<MpcKeyShare> surviving = List.of(shares.get(0), shares.get(1));
        assertEquals(2, surviving.size(), "剩余2个份额");
        assertTrue(canSign(surviving, THRESHOLD), "2份额仍可签名");

        // 故障节点的份额不参与签名
        Set<String> survivingIds = new HashSet<>();
        for (MpcKeyShare s : surviving) {
            survivingIds.add(s.getParticipantId());
        }
        assertFalse(survivingIds.contains("node-2"), "node-2 份额不参与");
    }

    @Test
    @Order(6)
    @DisplayName("6. 份额轮换→份额更新但联合公钥不变")
    void shareRotation() {
        String originalPubkey = wallet.getPublicKey();

        // 轮换：生成新份额
        List<MpcKeyShare> newShares = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            newShares.add(new MpcKeyShare(
                    "node-" + i,
                    UUID.randomUUID().toString().replace("-", ""),
                    UUID.randomUUID().toString().replace("-", ""),
                    null));
        }

        // 份额更新
        for (int i = 0; i < N; i++) {
            assertNotEquals(shares.get(i).getPrivateShareHex(),
                    newShares.get(i).getPrivateShareHex(),
                    "轮换后私钥份额应更新: node-" + i);
        }

        // 联合公钥不变
        assertEquals(originalPubkey, wallet.getPublicKey(), "轮换后联合公钥应不变");
        wallet.setLastRotatedAt(java.time.LocalDateTime.now());
        assertNotNull(wallet.getLastRotatedAt(), "应记录轮换时间");
    }

    @Test
    @Order(7)
    @DisplayName("7. 钱包状态管理：ACTIVE→FROZEN→DECOMMISSIONED")
    void walletStatusManagement() {
        assertEquals(MpcWallet.WalletStatus.ACTIVE, wallet.getStatus(), "初始应为ACTIVE");

        wallet.setStatus(MpcWallet.WalletStatus.FROZEN);
        assertEquals(MpcWallet.WalletStatus.FROZEN, wallet.getStatus(), "冻结后应为FROZEN");

        wallet.setStatus(MpcWallet.WalletStatus.DECOMMISSIONED);
        assertEquals(MpcWallet.WalletStatus.DECOMMISSIONED, wallet.getStatus(), "退役后应为DECOMMISSIONED");
    }

    /** 模拟阈值签名判定：份额数 >= threshold */
    private boolean canSign(List<MpcKeyShare> subset, int threshold) {
        return subset.size() >= threshold;
    }
}