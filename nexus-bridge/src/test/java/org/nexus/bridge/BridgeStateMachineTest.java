package org.nexus.bridge;

import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.model.BridgeTransaction.BridgeTxStatus;
import org.nexus.bridge.repository.BridgeTransactionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 桥状态机测试（2026-08-06 起使用真实 Ed25519 签名）。
 *
 * <p>多签验签修复后，MINT/UNLOCK 必须携带对确定性载荷的有效
 * Ed25519 签名且签名者位于配置白名单。本测试用 JDK 17 内置
 * Ed25519 生成真实密钥对与签名，走真实验签路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class BridgeStateMachineTest {

    @Mock
    private BridgeTransactionRepository txRepository;

    private BridgeConfig config;
    private BridgeServiceImpl bridgeService;

    private static KeyPair key1;
    private static KeyPair key2;
    private static KeyPair key3;
    private static String pub1;
    private static String pub2;
    private static String pub3;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        key1 = gen.generateKeyPair();
        key2 = gen.generateKeyPair();
        key3 = gen.generateKeyPair();
        pub1 = bytesToHex(key1.getPublic().getEncoded());
        pub2 = bytesToHex(key2.getPublic().getEncoded());
        pub3 = bytesToHex(key3.getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(50_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setLargeAmountThreshold(10_000_000_000L);
        config.setTimelockPeriodSeconds(3600);
        config.setValidatorPublicKeys(Arrays.asList(pub1, pub2, pub3));
        bridgeService = new BridgeServiceImpl(config, txRepository);
    }

    @Test
    @DisplayName("LOCK small amount -> immediate LOCKED status")
    void lock_smallAmount_immediateLocked() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LockRequest req = new LockRequest("nexus", "ethereum", 1_000_000L, "user1", "0xTarget", "0xTxHash1");
        BridgeTransaction tx = bridgeService.lock(req);
        assertEquals(BridgeTxStatus.LOCKED, tx.getStatus());
        assertNull(tx.getTimelockExpiresAt());
    }

    @Test
    @DisplayName("LOCK large amount -> LOCK_PENDING with timelock")
    void lock_largeAmount_pendingWithTimelock() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LockRequest req = new LockRequest("nexus", "ethereum", 20_000_000_000L, "user1", "0xTarget", "0xTxHash2");
        BridgeTransaction tx = bridgeService.lock(req);
        assertEquals(BridgeTxStatus.LOCK_PENDING, tx.getStatus());
        assertNotNull(tx.getTimelockExpiresAt());
    }

    @Test
    @DisplayName("LOCK exceeding max amount -> BridgeException")
    void lock_exceedsMax_throws() {
        LockRequest req = new LockRequest("nexus", "ethereum", 999_000_000_000L, "user1", "0xTarget", "0xTxHash3");
        assertThrows(BridgeException.class, () -> bridgeService.lock(req));
    }

    @Test
    @DisplayName("MINT with insufficient valid signatures -> BridgeException and lock tx marked FAILED")
    void mint_insufficientSigs_throws() {
        BridgeTransaction lockTx = newLockTx("lock-1");
        when(txRepository.findById("lock-1")).thenReturn(Optional.of(lockTx));

        // 只有 1 个真实有效签名（阈值 2）
        MintRequest req = new MintRequest("lock-1", null, pub1, "ethereum");
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pub1, sign(key1, mintPayload(lockTx, req.getTimestamp())));
        req.setSignatures(sigs);

        BridgeException ex = assertThrows(BridgeException.class, () -> bridgeService.mint(req));
        assertTrue(ex.getMessage().contains("Insufficient valid signatures"));

        // 修复点 2：失败后关联交易进入 FAILED 终态并记录原因
        assertEquals(BridgeTxStatus.FAILED, lockTx.getStatus());
        assertNotNull(lockTx.getFailureReason());
    }

    @Test
    @DisplayName("MINT with invalid signature content -> rejected (P1: verification, not just count)")
    void mint_invalidSignatureContent_rejected() {
        BridgeTransaction lockTx = newLockTx("lock-2");
        when(txRepository.findById("lock-2")).thenReturn(Optional.of(lockTx));

        // 2 个签名者但签名是伪造/无关内容 —— 修复前会因 size>=2 直接通过
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pub1, "deadbeef");
        sigs.put(pub2, "cafebabe");
        MintRequest req = new MintRequest("lock-2", sigs, pub1, "ethereum");
        BridgeException ex = assertThrows(BridgeException.class, () -> bridgeService.mint(req));
        assertTrue(ex.getMessage().contains("Insufficient valid signatures"));
        assertEquals(BridgeTxStatus.FAILED, lockTx.getStatus());
    }

    @Test
    @DisplayName("MINT with valid threshold signatures -> MINTED, only verified signers recorded")
    void mint_sufficientSigs_minted() {
        BridgeTransaction lockTx = newLockTx("lock-3");
        when(txRepository.findById("lock-3")).thenReturn(Optional.of(lockTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        long ts = System.currentTimeMillis();
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pub1, sign(key1, mintPayload(lockTx, ts)));
        sigs.put(pub2, sign(key2, mintPayload(lockTx, ts)));
        sigs.put("0xUnknownValidator", "00"); // 白名单外签名者，应被过滤
        MintRequest req = new MintRequest("lock-3", sigs, pub1, "ethereum");
        req.setTimestamp(ts);

        BridgeTransaction result = bridgeService.mint(req);
        assertEquals(BridgeTxStatus.MINTED, result.getStatus());
        assertEquals(new HashSet<>(Arrays.asList(pub1, pub2)), result.getValidatorIds());
        assertNotEquals(BridgeTxStatus.FAILED, lockTx.getStatus());
    }

    @Test
    @DisplayName("UNLOCK with valid threshold signatures -> UNLOCKED")
    void unlock_sufficientSigs_unlocked() {
        BridgeTransaction burnTx = new BridgeTransaction();
        burnTx.setTxId("burn-1");
        burnTx.setStatus(BridgeTxStatus.BURNED);
        burnTx.setSourceChainId("nexus");
        burnTx.setTargetChainId("ethereum");
        burnTx.setAmount(500_000L);
        burnTx.setTargetAddress("0xTarget");
        when(txRepository.findById("burn-1")).thenReturn(Optional.of(burnTx));
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        long ts = System.currentTimeMillis();
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pub1, sign(key1, unlockPayload(burnTx, ts)));
        sigs.put(pub2, sign(key2, unlockPayload(burnTx, ts)));
        UnlockRequest req = new UnlockRequest("burn-1", sigs, pub1, "nexus");
        req.setTimestamp(ts);

        BridgeTransaction result = bridgeService.unlock(req);
        assertEquals(BridgeTxStatus.UNLOCKED, result.getStatus());
        assertEquals(new HashSet<>(Arrays.asList(pub1, pub2)), result.getValidatorIds());
    }

    @Test
    @DisplayName("UNLOCK with insufficient signatures -> BridgeException and burn tx marked FAILED")
    void unlock_insufficientSigs_throws() {
        BridgeTransaction burnTx = new BridgeTransaction();
        burnTx.setTxId("burn-2");
        burnTx.setStatus(BridgeTxStatus.BURNED);
        burnTx.setSourceChainId("nexus");
        burnTx.setTargetChainId("ethereum");
        burnTx.setAmount(500_000L);
        burnTx.setTargetAddress("0xTarget");
        when(txRepository.findById("burn-2")).thenReturn(Optional.of(burnTx));

        long ts = System.currentTimeMillis();
        Map<String, String> sigs = new HashMap<>();
        sigs.put(pub1, sign(key1, unlockPayload(burnTx, ts)));
        UnlockRequest req = new UnlockRequest("burn-2", sigs, pub1, "nexus");
        req.setTimestamp(ts);

        assertThrows(BridgeException.class, () -> bridgeService.unlock(req));
        assertEquals(BridgeTxStatus.FAILED, burnTx.getStatus());
        assertNotNull(burnTx.getFailureReason());
    }

    @Test
    @DisplayName("PAUSE -> only UNLOCK allowed, LOCK rejected")
    void pause_blocksLock() {
        bridgeService.pause("v1");
        LockRequest req = new LockRequest("nexus", "ethereum", 1_000_000L, "user1", "0xTarget", "0xTxHash4");
        assertThrows(BridgeException.class, () -> bridgeService.lock(req));
    }

    @Test
    @DisplayName("Daily limit exceeded -> BridgeException")
    void lock_dailyLimitExceeded_throws() {
        when(txRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Use up most of the daily limit (each under 50B max per tx)
        LockRequest first = new LockRequest("nexus", "ethereum", 40_000_000_000L, "u", "t", "h1");
        bridgeService.lock(first);
        LockRequest second = new LockRequest("nexus", "ethereum", 40_000_000_000L, "u", "t", "h2");
        bridgeService.lock(second);
        // 40B + 40B = 80B used. This 30B would make 110B > 100B daily limit
        LockRequest over = new LockRequest("nexus", "ethereum", 30_000_000_000L, "u", "t", "h3");
        assertThrows(BridgeException.class, () -> bridgeService.lock(over));
    }

    // ---- helpers ----

    private static BridgeTransaction newLockTx(String txId) {
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setTxId(txId);
        lockTx.setStatus(BridgeTxStatus.LOCKED);
        lockTx.setSourceChainId("nexus");
        lockTx.setTargetChainId("ethereum");
        lockTx.setAmount(500_000L);
        lockTx.setTargetAddress("0xTarget");
        return lockTx;
    }

    /** 与 BridgeServiceImpl.mintPayload 相同的载荷构造。 */
    private static String mintPayload(BridgeTransaction lockTx, long ts) {
        return BridgeValidator.buildPayload(lockTx.getSourceChainId(), lockTx.getTxId(),
                lockTx.getAmount(), lockTx.getTargetAddress(), ts);
    }

    /** 与 BridgeServiceImpl.unlockPayload 相同的载荷构造。 */
    private static String unlockPayload(BridgeTransaction burnTx, long ts) {
        return BridgeValidator.buildPayload(burnTx.getTargetChainId(), burnTx.getTxId(),
                burnTx.getAmount(), burnTx.getTargetAddress(), ts);
    }

    private static String sign(KeyPair keyPair, String payload) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(keyPair.getPrivate());
            sig.update(payload.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(sig.sign());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        return new AbstractMap.SimpleEntry<>(k, v);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
