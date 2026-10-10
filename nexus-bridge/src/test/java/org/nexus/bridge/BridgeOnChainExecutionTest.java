package org.nexus.bridge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.config.BridgeChainProperties;
import org.nexus.bridge.handler.BridgeOnChainExecutor;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.repository.BridgeTransactionRepository;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 桥链上执行接线回归测试（P0 修复 2026-10-09）。
 *
 * <p>背景：桥的 {@code mint}/{@code unlock} 此前只改数据库并标 {@code MINTED}/{@code UNLOCKED}，
 * {@code targetTxHash} 恒为 null —— "报告已铸造但目标链上什么都没发生"（handler 系
 * 零生产调用方）。本测试钉住：</p>
 * <ol>
 *   <li>executor 按配置判定"可上链"（rpc + 合约 + 合法私钥三者齐备且链受支持）；</li>
 *   <li>注入 executor 后，mint/unlock **必须**经过广播：拿到真哈希写入 {@code targetTxHash}；</li>
 *   <li>不可上链且 {@code mockMode=false} → **拒绝**（fail-closed，置 FAILED 并抛错）；</li>
 *   <li>{@code mockMode=true} → 放行（仅开发/测试，记 warn）；</li>
 *   <li>executor 为 null（旧装配/单测）→ 保持历史行为（不广播）。</li>
 * </ol>
 */
class BridgeOnChainExecutionTest {

    /** 公开的以太坊测试私钥（Hardhat 常用第一账户），仅用于单测。 */
    private static final String TEST_EVM_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";

    private KeyPair key1;
    private KeyPair key2;
    private String pub1;
    private String pub2;

    private BridgeConfig config;
    private BridgeTransactionRepository txRepository;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        key1 = gen.generateKeyPair();
        key2 = gen.generateKeyPair();
        pub1 = bytesToHex(key1.getPublic().getEncoded());
        pub2 = bytesToHex(key2.getPublic().getEncoded());

        config = new BridgeConfig();
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(50_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setLargeAmountThreshold(10_000_000_000L);
        config.setTimelockPeriodSeconds(3600);
        config.setValidatorPublicKeys(Arrays.asList(pub1, pub2));
        txRepository = mock(BridgeTransactionRepository.class);
    }

    // ==================== executor：配置判定 ====================

    @Test
    @DisplayName("executor：未配置任何链 → ethereum 不可上链")
    void executor_notConfigured_unavailable() {
        BridgeOnChainExecutor executor = new BridgeOnChainExecutor(config, new BridgeChainProperties());
        assertFalse(executor.isOnChainAvailable("ethereum"));
    }

    @Test
    @DisplayName("executor：非 ethereum 链暂不支持真广播（BSC/Avalanche handler 无凭证路径）")
    void executor_unsupportedChain_unavailable() {
        BridgeChainProperties props = new BridgeChainProperties();
        props.getBsc().setRpcEndpoint("https://bsc.example");
        props.getBsc().setContractAddress("0xBridge");
        props.getBsc().setRelayerPrivateKey(TEST_EVM_KEY);
        BridgeOnChainExecutor executor = new BridgeOnChainExecutor(config, props);
        assertFalse(executor.isOnChainAvailable("bsc"),
                "BSC handler 无凭证构造路径，应判为不可上链（诚实覆盖范围）");
    }

    @Test
    @DisplayName("executor：rpc + 合约 + 合法私钥齐备 → ethereum 可上链")
    void executor_configured_available() {
        BridgeOnChainExecutor executor = new BridgeOnChainExecutor(config, ethereumReady(propsWithKey(TEST_EVM_KEY)));
        assertTrue(executor.isOnChainAvailable("ethereum"));
    }

    @Test
    @DisplayName("executor：私钥非法（非 hex）→ 装配失败视为不可上链（fail-closed，不静默）")
    void executor_invalidKey_unavailable() {
        BridgeOnChainExecutor executor = new BridgeOnChainExecutor(config, ethereumReady(propsWithKey("not-a-hex-key")));
        assertFalse(executor.isOnChainAvailable("ethereum"));
    }

    // ==================== 服务挂钩：fail-closed / 真哈希 ====================

    @Test
    @DisplayName("mint：不可上链且 mockMode=false → 拒绝（FAILED + 抛错，不再假装 MINTED）")
    void mint_onChainUnavailable_notMockMode_failsClosed() throws Exception {
        BridgeOnChainExecutor executor = mock(BridgeOnChainExecutor.class);
        when(executor.broadcastMint(anyString(), any(), any())).thenReturn(Optional.empty());
        BridgeServiceImpl service = serviceWithExecutor(executor);

        BridgeTransaction lockTx = newLockTx("lock-fail");
        when(txRepository.findById("lock-fail")).thenReturn(Optional.of(lockTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MintRequest req = mintRequest(lockTx, "lock-fail");
        BridgeException ex = assertThrows(BridgeException.class, () -> service.mint(req));
        assertTrue(ex.getMessage().contains("refusing to mark MINTED"),
                "错误信息应说明拒标 MINTED 的原因，实际=" + ex.getMessage());
        assertNotEquals(BridgeTransaction.BridgeTxStatus.MINTED, lockTx.getStatus(), "不得标 MINTED");
    }

    @Test
    @DisplayName("mint：拿到真链上哈希 → 写入 targetTxHash 且 MINTED")
    void mint_onChainBroadcast_setsTargetTxHash() throws Exception {
        BridgeOnChainExecutor executor = mock(BridgeOnChainExecutor.class);
        when(executor.broadcastMint(anyString(), any(), any())).thenReturn(Optional.of("0xREALMINT"));
        BridgeServiceImpl service = serviceWithExecutor(executor);

        BridgeTransaction lockTx = newLockTx("lock-ok");
        when(txRepository.findById("lock-ok")).thenReturn(Optional.of(lockTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BridgeTransaction result = service.mint(mintRequest(lockTx, "lock-ok"));

        assertEquals(BridgeTransaction.BridgeTxStatus.MINTED, result.getStatus());
        assertEquals("0xREALMINT", result.getTargetTxHash(), "真链上哈希必须落库（此前恒 null）");
    }

    @Test
    @DisplayName("mint：mockMode=true → 放行（仅开发/测试；仍不写假哈希）")
    void mint_mockMode_allowsWithoutHash() throws Exception {
        config.setMockMode(true);
        BridgeOnChainExecutor executor = mock(BridgeOnChainExecutor.class);
        when(executor.broadcastMint(anyString(), any(), any())).thenReturn(Optional.empty());
        BridgeServiceImpl service = serviceWithExecutor(executor);

        BridgeTransaction lockTx = newLockTx("lock-mock");
        when(txRepository.findById("lock-mock")).thenReturn(Optional.of(lockTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BridgeTransaction result = service.mint(mintRequest(lockTx, "lock-mock"));

        assertEquals(BridgeTransaction.BridgeTxStatus.MINTED, result.getStatus());
        assertNull(result.getTargetTxHash(), "mock 模式不写哈希（不伪造链上事实）");
    }

    @Test
    @DisplayName("unlock：不可上链且 mockMode=false → 拒绝（fail-closed）")
    void unlock_onChainUnavailable_failsClosed() {
        BridgeOnChainExecutor executor = mock(BridgeOnChainExecutor.class);
        when(executor.broadcastUnlock(anyString(), any(), any())).thenReturn(Optional.empty());
        BridgeServiceImpl service = serviceWithExecutor(executor);

        BridgeTransaction burnTx = newBurnTx("burn-fail");
        when(txRepository.findById("burn-fail")).thenReturn(Optional.of(burnTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        long ts = System.currentTimeMillis();
        UnlockRequest req = new UnlockRequest("burn-fail", burnSigs(burnTx, ts), pub1, "ethereum");
        req.setTimestamp(ts);
        BridgeException ex = assertThrows(BridgeException.class, () -> service.unlock(req));
        assertTrue(ex.getMessage().contains("refusing to mark UNLOCKED"), ex.getMessage());
        assertNotEquals(BridgeTransaction.BridgeTxStatus.UNLOCKED, burnTx.getStatus());
    }

    @Test
    @DisplayName("兼容：executor 为 null（旧装配/单测）→ mint 保持历史行为")
    void mint_legacyAssembly_keepsHistoricalBehavior() throws Exception {
        BridgeServiceImpl service = new BridgeServiceImpl(config, txRepository);

        BridgeTransaction lockTx = newLockTx("lock-legacy");
        when(txRepository.findById("lock-legacy")).thenReturn(Optional.of(lockTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BridgeTransaction result = service.mint(mintRequest(lockTx, "lock-legacy"));
        assertEquals(BridgeTransaction.BridgeTxStatus.MINTED, result.getStatus());
    }

    // ==================== 辅助 ====================

    private BridgeServiceImpl serviceWithExecutor(BridgeOnChainExecutor executor) {
        return new BridgeServiceImpl(config, txRepository, null, null, null, executor);
    }

    private static BridgeChainProperties propsWithKey(String key) {
        BridgeChainProperties props = new BridgeChainProperties();
        props.getEthereum().setRpcEndpoint("https://eth.example");
        props.getEthereum().setChainId("0x1");
        props.getEthereum().setContractAddress("0xBridgeSource");
        props.getEthereum().setNexusTokenAddress("0xNex");
        props.getEthereum().setRelayerPrivateKey(key);
        return props;
    }

    private static BridgeChainProperties ethereumReady(BridgeChainProperties props) {
        return props;
    }

    private BridgeTransaction newLockTx(String txId) {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId(txId);
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        tx.setSourceChainId("nexus");
        tx.setTargetChainId("ethereum");
        tx.setAmount(1_000_000L);
        tx.setUserAddress("user1");
        tx.setTargetAddress("0xTarget");
        tx.setValidatorIds(new HashSet<>());
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());
        return tx;
    }

    private BridgeTransaction newBurnTx(String txId) {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId(txId);
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_BURN);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.BURNED);
        tx.setSourceChainId("ethereum");
        tx.setTargetChainId("ethereum");
        tx.setAmount(1_000_000L);
        tx.setUserAddress("user1");
        tx.setTargetAddress("0xTarget");
        tx.setValidatorIds(new HashSet<>());
        tx.setCreatedAt(Instant.now());
        tx.setUpdatedAt(Instant.now());
        return tx;
    }

    private MintRequest mintRequest(BridgeTransaction lockTx, String lockTxId) throws Exception {
        long ts = System.currentTimeMillis();
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pub1, sign(key1, BridgeValidator.buildPayload(lockTx.getSourceChainId(), lockTx.getTxId(),
                lockTx.getAmount(), lockTx.getTargetAddress(), ts)));
        sigs.put(pub2, sign(key2, BridgeValidator.buildPayload(lockTx.getSourceChainId(), lockTx.getTxId(),
                lockTx.getAmount(), lockTx.getTargetAddress(), ts)));
        MintRequest req = new MintRequest(lockTxId, sigs, pub1, "ethereum");
        req.setTimestamp(ts);
        return req;
    }

    private Map<String, String> burnSigs(BridgeTransaction burnTx, long ts) {
        try {
            Map<String, String> sigs = new HashMap<>();
            sigs.put(pub1, sign(key1, BridgeValidator.buildPayload(burnTx.getTargetChainId(), burnTx.getTxId(),
                    burnTx.getAmount(), burnTx.getTargetAddress(), ts)));
            sigs.put(pub2, sign(key2, BridgeValidator.buildPayload(burnTx.getTargetChainId(), burnTx.getTxId(),
                    burnTx.getAmount(), burnTx.getTargetAddress(), ts)));
            return sigs;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(KeyPair keyPair, String payload) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytesToHex(sig.sign());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
