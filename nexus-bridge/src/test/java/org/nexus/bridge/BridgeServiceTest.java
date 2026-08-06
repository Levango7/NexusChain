package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.handler.AbstractBridgeHandler;
import org.nexus.bridge.handler.EthereumBridgeHandler;
import org.nexus.bridge.handler.BSCBridgeHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BridgeService 基础测试骨架。
 *
 * <p>本测试类验证跨链桥模块的核心逻辑骨架，包括配置校验、
 * 状态枚举、DTO 构建以及处理器模板方法的基础行为。</p>
 *
 * <p>2026-08-06：新增 BridgeValidator 真实 Ed25519 验签用例
 * （多签只数个数不验签的 P1 修复回归测试）。</p>
 *
 * @since 1.0.0
 */
class BridgeServiceTest {

    /** 测试用桥配置。 */
    private BridgeConfig config;

    /** 真实 Ed25519 密钥对（JDK 17 内置算法）。 */
    private static KeyPair keyA;
    private static KeyPair keyB;
    private static String pubA;
    private static String pubB;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        keyA = gen.generateKeyPair();
        keyB = gen.generateKeyPair();
        pubA = bytesToHex(keyA.getPublic().getEncoded());
        pubB = bytesToHex(keyB.getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSourceChainId("ethereum");
        config.setTargetChainId("bsc");
        config.setSignatureThreshold(3);
        config.setTimelockPeriodSeconds(3600);
        config.setMaxAmountPerTx(10_000_000_000L); // 10000 NEX
        config.setDailyLimit(100_000_000_000L);     // 100000 NEX
        config.setLargeAmountThreshold(8_000_000_000L); // 8B threshold
        config.setValidatorPublicKeys(Arrays.asList(
                "0xvalidator1",
                "0xvalidator2",
                "0xvalidator3",
                "0xvalidator4",
                "0xvalidator5"
        ));
    }

    // ==================== BridgeState 测试 ====================

    @Test
    @DisplayName("BridgeState 枚举应包含 ACTIVE、PAUSED、EMERGENCY_STOP 三个状态")
    void testBridgeStateEnumValues() {
        BridgeState[] states = BridgeState.values();
        assertEquals(3, states.length);
        assertTrue(Arrays.asList(states).contains(BridgeState.ACTIVE));
        assertTrue(Arrays.asList(states).contains(BridgeState.PAUSED));
        assertTrue(Arrays.asList(states).contains(BridgeState.EMERGENCY_STOP));
    }

    // ==================== BridgeConfig 测试 ====================

    @Test
    @DisplayName("BridgeConfig 金额上限检查应正确判断是否超过单笔上限")
    void testConfigExceedsMaxAmount() {
        assertFalse(config.exceedsMaxAmount(5_000_000_000L)); // 5 NEX, 不超过
        assertFalse(config.exceedsMaxAmount(10_000_000_000L)); // 10000 NEX, 等于上限
        assertTrue(config.exceedsMaxAmount(10_001_000_000L)); // 超过上限
    }

    @Test
    @DisplayName("BridgeConfig 大额检查应正确判断是否需要时间锁")
    void testConfigIsLargeAmount() {
        assertFalse(config.isLargeAmount(4_999_000_000L)); // 低于大额阈值, 非大额
        assertFalse(config.isLargeAmount(5_000_000_000L)); // 5 NEX, 非大额
        assertTrue(config.isLargeAmount(10_000_000_000L)); // 10000 NEX, 大额
    }

    @Test
    @DisplayName("BridgeConfig 日限额检查应正确判断累计是否超过日限额")
    void testConfigExceedsDailyLimit() {
        long dailyUsed = 90_000_000_000L; // 已用 90000 NEX
        assertFalse(config.exceedsDailyLimit(5_000_000_000L, dailyUsed)); // 5B + 90B < 100B, 不超
        assertTrue(config.exceedsDailyLimit(20_000_000_000L, dailyUsed)); // 20000, 超限
    }

    // ==================== LockRequest 测试 ====================

    @Test
    @DisplayName("LockRequest 应正确构建并保留所有字段")
    void testLockRequestConstruction() {
        LockRequest request = new LockRequest(
                "ethereum", "bsc",
                1_000_000_000_000_000_000L, // 1000 NEX
                "0xUserAddress",
                "0xTargetAddress",
                "0xSourceTxHash"
        );

        assertEquals("ethereum", request.getSourceChainId());
        assertEquals("bsc", request.getTargetChainId());
        assertEquals(1_000_000_000_000_000_000L, request.getAmount());
        assertEquals("0xUserAddress", request.getUserAddress());
        assertEquals("0xTargetAddress", request.getTargetAddress());
        assertEquals("0xSourceTxHash", request.getSourceTxHash());
        assertTrue(request.getTimestamp() > 0);
    }

    // ==================== MintRequest 测试 ====================

    @Test
    @DisplayName("MintRequest 签名计数应正确反映签名集合大小")
    void testMintRequestSignatureCount() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("validator1", "0xsig1");
        sigs.put("validator2", "0xsig2");
        sigs.put("validator3", "0xsig3");

        MintRequest request = new MintRequest("tx-001", sigs, "0xminter", "bsc");

        assertEquals(3, request.getSignatureCount());
        assertEquals("tx-001", request.getLockTxId());
        assertEquals("0xminter", request.getMinterAddress());
    }

    @Test
    @DisplayName("MintRequest 空签名集合应返回 0")
    void testMintRequestEmptySignatures() {
        MintRequest request = new MintRequest();
        assertEquals(0, request.getSignatureCount());
    }

    // ==================== BridgeValidator 多签阈值测试 ====================

    @Test
    @DisplayName("BridgeValidator.meetsThreshold 应正确判断签名是否达到阈值")
    void testValidatorMeetsThreshold() {
        java.util.Set<String> sigs3 = new java.util.HashSet<>(Arrays.asList("v1", "v2", "v3"));
        java.util.Set<String> sigs2 = new java.util.HashSet<>(Arrays.asList("v1", "v2"));

        assertTrue(BridgeValidator.meetsThreshold(sigs3, config)); // 3 >= 3
        assertFalse(BridgeValidator.meetsThreshold(sigs2, config)); // 2 < 3
    }

    @Test
    @DisplayName("BridgeValidator.meetsThreshold 空集合或 null 应返回 false")
    void testValidatorMeetsThresholdEdgeCases() {
        assertFalse(BridgeValidator.meetsThreshold(null, config));
        assertFalse(BridgeValidator.meetsThreshold(new java.util.HashSet<>(), config));
        assertFalse(BridgeValidator.meetsThreshold(new java.util.HashSet<>(Arrays.asList("v1")), null));
    }

    // ==================== BridgeValidator 验签测试（P1 修复回归） ====================

    @Test
    @DisplayName("BridgeValidator 应接受白名单内验证者的真实 Ed25519 签名")
    void testVerifySignatureAcceptsValidSignature() throws Exception {
        BridgeConfig cryptoConfig = new BridgeConfig();
        cryptoConfig.setSignatureThreshold(1);
        cryptoConfig.setValidatorPublicKeys(Arrays.asList(pubA, pubB));

        String payload = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        String sig = sign(keyA, payload);

        assertTrue(BridgeValidator.verifySignature(payload, pubA, sig, cryptoConfig));
    }

    @Test
    @DisplayName("BridgeValidator 应拒绝白名单外签名者（即使签名有效）")
    void testVerifySignatureRejectsUnknownSigner() throws Exception {
        BridgeConfig cryptoConfig = new BridgeConfig();
        cryptoConfig.setSignatureThreshold(1);
        cryptoConfig.setValidatorPublicKeys(Arrays.asList(pubA));

        String payload = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        String sig = sign(keyB, payload); // keyB 不在白名单

        assertFalse(BridgeValidator.verifySignature(payload, pubB, sig, cryptoConfig));
    }

    @Test
    @DisplayName("BridgeValidator 应拒绝篡改载荷的签名")
    void testVerifySignatureRejectsTamperedPayload() throws Exception {
        BridgeConfig cryptoConfig = new BridgeConfig();
        cryptoConfig.setSignatureThreshold(1);
        cryptoConfig.setValidatorPublicKeys(Arrays.asList(pubA));

        String payload = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        String tampered = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 999999999L);
        String sig = sign(keyA, payload);

        assertFalse(BridgeValidator.verifySignature(tampered, pubA, sig, cryptoConfig));
    }

    @Test
    @DisplayName("BridgeValidator 应拒绝垃圾签名与非法公钥格式（fail-closed）")
    void testVerifySignatureRejectsGarbage() {
        BridgeConfig cryptoConfig = new BridgeConfig();
        cryptoConfig.setSignatureThreshold(1);
        cryptoConfig.setValidatorPublicKeys(Arrays.asList(pubA, "0xnot-a-key"));

        String payload = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        assertFalse(BridgeValidator.verifySignature(payload, pubA, "deadbeef", cryptoConfig));
        // 白名单条目无法解码为公钥 → 拒绝
        assertFalse(BridgeValidator.verifySignature(payload, "0xnot-a-key", "deadbeef", cryptoConfig));
        // null 入参 → 拒绝
        assertFalse(BridgeValidator.verifySignature(payload, null, "deadbeef", cryptoConfig));
    }

    @Test
    @DisplayName("BridgeValidator.filterValidSignatures 应只保留验签通过的签名者")
    void testFilterValidSignatures() throws Exception {
        BridgeConfig cryptoConfig = new BridgeConfig();
        cryptoConfig.setSignatureThreshold(2);
        cryptoConfig.setValidatorPublicKeys(Arrays.asList(pubA, pubB));

        String payload = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pubA, sign(keyA, payload));   // 有效
        sigs.put(pubB, "cafebabe");            // 签名无效
        sigs.put("0xIntruder", sign(keyA, payload)); // 白名单外

        java.util.Set<String> valid = BridgeValidator.filterValidSignatures(payload, sigs, cryptoConfig);
        assertEquals(1, valid.size());
        assertTrue(valid.contains(pubA));

        // null 集合 → 空集合
        assertTrue(BridgeValidator.filterValidSignatures(payload, null, cryptoConfig).isEmpty());
    }

    @Test
    @DisplayName("BridgeValidator.buildPayload 对相同输入应确定性一致、对不同输入应不同")
    void testBuildPayloadDeterministic() {
        String p1 = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        String p2 = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456789L);
        String p3 = BridgeValidator.buildPayload("nexus", "tx-1", 1000L, "0xTarget", 123456790L);

        assertEquals(p1, p2);
        assertEquals(64, p1.length());
        assertNotEquals(p1, p3);
    }

    // ==================== BridgeTransaction 测试 ====================

    @Test
    @DisplayName("BridgeTransaction 终态判断应正确识别 MINTED、UNLOCKED、FAILED 等")
    void testTransactionIsTerminal() {
        BridgeTransaction tx = new BridgeTransaction();

        tx.setStatus(BridgeTransaction.BridgeTxStatus.MINTED);
        assertTrue(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.UNLOCKED);
        assertTrue(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.FAILED);
        assertTrue(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.CANCELLED);
        assertTrue(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.TIMEOUT);
        assertTrue(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCK_PENDING);
        assertFalse(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        assertFalse(tx.isTerminal());

        tx.setStatus(BridgeTransaction.BridgeTxStatus.MINT_PENDING);
        assertFalse(tx.isTerminal());
    }

    @Test
    @DisplayName("BridgeTransaction failureReason 字段应可读写（FAILED 终态原因记录）")
    void testTransactionFailureReason() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setFailureReason("Insufficient valid signatures");
        assertEquals("Insufficient valid signatures", tx.getFailureReason());
        tx.setFailureReason(null);
        assertNull(tx.getFailureReason());
    }

    // ==================== BridgeStatus 测试 ====================

    @Test
    @DisplayName("BridgeStatus 剩余额度应正确计算")
    void testBridgeStatusDailyRemaining() {
        BridgeStatus status = new BridgeStatus();
        status.setDailyLimit(100_000_000_000L);
        status.setDailyUsed(30_000_000_000L);

        assertEquals(70_000_000_000L, status.getDailyRemaining());
    }

    @Test
    @DisplayName("BridgeStatus 已用超额时剩余应为 0")
    void testBridgeStatusDailyRemainingClamped() {
        BridgeStatus status = new BridgeStatus();
        status.setDailyLimit(100L);
        status.setDailyUsed(150L);

        assertEquals(0, status.getDailyRemaining());
    }

    // ==================== Handler 基础测试 ====================

    @Test
    @DisplayName("EthereumBridgeHandler 应返回正确的链 ID")
    void testEthereumHandlerChainId() {
        EthereumBridgeHandler handler = new EthereumBridgeHandler(config);
        assertEquals("ethereum", handler.getChainId());
    }

    @Test
    @DisplayName("BSCBridgeHandler 应返回正确的链 ID")
    void testBSCHandlerChainId() {
        BSCBridgeHandler handler = new BSCBridgeHandler(config);
        assertEquals("bsc", handler.getChainId());
    }

    @Test
    @DisplayName("AbstractBridgeHandler 锁定操作应校验金额为正数")
    void testHandlerLockRejectsNonPositiveAmount() {
        AbstractBridgeHandler handler = new EthereumBridgeHandler(config);
        LockRequest request = new LockRequest("ethereum", "bsc", 0, "0xUser", "0xTarget", "0xHash");

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(request));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("AbstractBridgeHandler 锁定操作应拒绝超过单笔上限的金额")
    void testHandlerLockRejectsExceedingMaxAmount() {
        AbstractBridgeHandler handler = new EthereumBridgeHandler(config);
        LockRequest request = new LockRequest(
                "ethereum", "bsc",
                20_000_000_000L, // 20000 NEX, 超过 10000 上限
                "0xUser", "0xTarget", "0xHash"
        );

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(request));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    @Test
    @DisplayName("AbstractBridgeHandler 应正确更新桥配置")
    void testHandlerUpdateConfig() {
        AbstractBridgeHandler handler = new BSCBridgeHandler(config);
        BridgeConfig newConfig = new BridgeConfig();
        newConfig.setMaxAmountPerTx(50_000_000_000L);
        handler.updateConfig(newConfig);

        // 更新后原上限不再触发
        LockRequest request = new LockRequest(
                "bsc", "ethereum",
                20_000_000_000L, // 20000 NEX, 不再超过新上限 50000
                "0xUser", "0xTarget", "0xHash"
        );
        // 金额校验通过后，submitLockTransaction 尚未实现会抛出 UnsupportedOperationException
        RuntimeException ex = assertThrows(RuntimeException.class, () -> handler.lock(request));
        // 确保不是金额超限异常
        if (ex instanceof BridgeException) {
            assertNotEquals("AMOUNT_EXCEEDS_LIMIT", ((BridgeException) ex).getErrorCode());
        }
    }

    // ==================== 测试辅助 ====================

    private static String sign(KeyPair keyPair, String payload) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(keyPair.getPrivate());
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(sig.sign());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
