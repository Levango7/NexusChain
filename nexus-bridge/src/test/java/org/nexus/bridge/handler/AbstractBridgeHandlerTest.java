package org.nexus.bridge.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.bridge.*;
import org.nexus.bridge.model.BridgeTransaction;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AbstractBridgeHandler} 模板方法单元测试：
 * 覆盖 lock/mint/burn/unlock 的参数校验、限额检查与状态流转。
 *
 * <p>使用可注入 submit 结果的测试子类，避免依赖真实 Web3j。</p>
 */
class AbstractBridgeHandlerTest {

    private BridgeConfig config;
    private TestableHandler handler;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSourceChainId("ethereum");
        config.setTargetChainId("bsc");
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(10_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setLargeAmountThreshold(8_000_000_000L);
        config.setValidatorPublicKeys(Arrays.asList("v1", "v2", "v3"));
        handler = new TestableHandler(config);
    }

    // ==================== lock 测试 ====================

    @Test
    @DisplayName("lock: 合法请求应返回 LOCK_PENDING 状态的桥交易")
    void lock_validRequest_returnsLockPending() {
        handler.nextTxHash = "0xlockhash";
        LockRequest req = new LockRequest("ethereum", "bsc", 1000L, "0xUser", "0xTarget", "0xSrcHash");

        BridgeTransaction tx = handler.lock(req);

        assertEquals(BridgeTransaction.BridgeTxStatus.LOCK_PENDING, tx.getStatus());
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK, tx.getOperationType());
        assertEquals("0xlockhash", tx.getSourceTxHash());
        assertEquals("ethereum", tx.getSourceChainId());
        assertEquals("bsc", tx.getTargetChainId());
        assertEquals(1000L, tx.getAmount());
        assertEquals("0xUser", tx.getUserAddress());
        assertEquals("0xTarget", tx.getTargetAddress());
        assertNotNull(tx.getCreatedAt());
        assertNotNull(tx.getUpdatedAt());
    }

    @Test
    @DisplayName("lock: null 请求应抛 INVALID_REQUEST")
    void lock_nullRequestThrows() {
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 零金额应抛 INVALID_AMOUNT")
    void lock_zeroAmountThrows() {
        LockRequest req = new LockRequest("ethereum", "bsc", 0, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 负金额应抛 INVALID_AMOUNT")
    void lock_negativeAmountThrows() {
        LockRequest req = new LockRequest("ethereum", "bsc", -100, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 空 userAddress 应抛 INVALID_ADDRESS")
    void lock_emptyUserAddressThrows() {
        LockRequest req = new LockRequest("ethereum", "bsc", 100, "", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: null userAddress 应抛 INVALID_ADDRESS")
    void lock_nullUserAddressThrows() {
        LockRequest req = new LockRequest("ethereum", "bsc", 100, null, "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 空 targetAddress 应抛 INVALID_ADDRESS")
    void lock_emptyTargetAddressThrows() {
        LockRequest req = new LockRequest("ethereum", "bsc", 100, "0xUser", "", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 超过单笔上限应抛 AMOUNT_EXCEEDS_LIMIT")
    void lock_exceedsMaxAmountThrows() {
        LockRequest req = new LockRequest("ethereum", "bsc", 20_000_000_000L, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    // ==================== mint 测试 ====================

    @Test
    @DisplayName("mint: 合法请求应将 lockTx 状态置为 MINT_PENDING")
    void mint_validRequest_setsMintPending() {
        handler.nextTxHash = "0xmintHash";
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        MintRequest req = new MintRequest("lock-1", sigs, "0xMinter", "bsc");
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);

        BridgeTransaction result = handler.mint(req, lockTx);

        assertEquals(BridgeTransaction.BridgeTxStatus.MINT_PENDING, result.getStatus());
        assertEquals("0xmintHash", result.getTargetTxHash());
    }

    @Test
    @DisplayName("mint: null request 应抛 INVALID_REQUEST")
    void mint_nullRequestThrows() {
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(null, lockTx));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: null lockTx 应抛 INVALID_REQUEST")
    void mint_nullLockTxThrows() {
        MintRequest req = new MintRequest("lock-1", new HashMap<>(), "0xM", "bsc");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: 签名不足应抛 INSUFFICIENT_SIGNATURES")
    void mint_insufficientSignaturesThrows() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        MintRequest req = new MintRequest("lock-1", sigs, "0xM", "bsc");
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: lockTx 非 LOCKED 状态应抛 INVALID_LOCK_STATE")
    void mint_invalidLockStateThrows() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        MintRequest req = new MintRequest("lock-1", sigs, "0xM", "bsc");
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCK_PENDING);

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INVALID_LOCK_STATE", ex.getErrorCode());
    }

    // ==================== burn 测试 ====================

    @Test
    @DisplayName("burn: 合法请求应返回 BURN_PENDING 状态")
    void burn_validRequest_returnsBurnPending() {
        handler.nextTxHash = "0xburnHash";
        BurnRequest req = new BurnRequest("bsc", "ethereum", 1000L, "0xUser", "0xTarget", "0xHash");

        BridgeTransaction tx = handler.burn(req);

        assertEquals(BridgeTransaction.BridgeTxStatus.BURN_PENDING, tx.getStatus());
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_BURN, tx.getOperationType());
        assertEquals("0xburnHash", tx.getSourceTxHash());
    }

    @Test
    @DisplayName("burn: null 请求应抛 INVALID_REQUEST")
    void burn_nullRequestThrows() {
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 零金额应抛 INVALID_AMOUNT")
    void burn_zeroAmountThrows() {
        BurnRequest req = new BurnRequest("bsc", "ethereum", 0, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 空 userAddress 应抛 INVALID_ADDRESS")
    void burn_emptyUserAddressThrows() {
        BurnRequest req = new BurnRequest("bsc", "ethereum", 100, "", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 超过单笔上限应抛 AMOUNT_EXCEEDS_LIMIT")
    void burn_exceedsMaxAmountThrows() {
        BurnRequest req = new BurnRequest("bsc", "ethereum", 20_000_000_000L, "0xUser", "0xTarget", "0xHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    // ==================== unlock 测试 ====================

    @Test
    @DisplayName("unlock: 合法请求应将 burnTx 状态置为 UNLOCK_PENDING")
    void unlock_validRequest_setsUnlockPending() {
        handler.nextTxHash = "0xunlockHash";
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        UnlockRequest req = new UnlockRequest("burn-1", sigs, "0xUnlocker", "ethereum");
        BridgeTransaction burnTx = new BridgeTransaction();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.BURNED);

        BridgeTransaction result = handler.unlock(req, burnTx);

        assertEquals(BridgeTransaction.BridgeTxStatus.UNLOCK_PENDING, result.getStatus());
        assertEquals("0xunlockHash", result.getTargetTxHash());
    }

    @Test
    @DisplayName("unlock: null request 应抛 INVALID_REQUEST")
    void unlock_nullRequestThrows() {
        BridgeTransaction burnTx = new BridgeTransaction();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.BURNED);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(null, burnTx));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: null burnTx 应抛 INVALID_REQUEST")
    void unlock_nullBurnTxThrows() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        UnlockRequest req = new UnlockRequest("burn-1", sigs, "0xU", "ethereum");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: 签名不足应抛 INSUFFICIENT_SIGNATURES")
    void unlock_insufficientSignaturesThrows() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        UnlockRequest req = new UnlockRequest("burn-1", sigs, "0xU", "ethereum");
        BridgeTransaction burnTx = new BridgeTransaction();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.BURNED);

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: burnTx 非 BURNED 状态应抛 INVALID_BURN_STATE")
    void unlock_invalidBurnStateThrows() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        UnlockRequest req = new UnlockRequest("burn-1", sigs, "0xU", "ethereum");
        BridgeTransaction burnTx = new BridgeTransaction();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.BURN_PENDING);

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INVALID_BURN_STATE", ex.getErrorCode());
    }

    // ==================== updateConfig 测试 ====================

    @Test
    @DisplayName("updateConfig: 应替换内部配置")
    void updateConfig_replacesConfig() {
        BridgeConfig newConfig = new BridgeConfig();
        newConfig.setSignatureThreshold(5);
        newConfig.setMaxAmountPerTx(50_000_000_000L);
        newConfig.setValidatorPublicKeys(Arrays.asList("v1", "v2", "v3", "v4", "v5"));
        handler.updateConfig(newConfig);

        // 阈值变为 5，2 个签名不再满足
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "s1");
        sigs.put("v2", "s2");
        MintRequest req = new MintRequest("lock-1", sigs, "0xM", "bsc");
        BridgeTransaction lockTx = new BridgeTransaction();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);

        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    // ==================== 测试子类 ====================

    /**
     * 测试用处理器子类：跳过真实 Web3j 调用，返回预设的交易哈希。
     */
    static class TestableHandler extends AbstractBridgeHandler {
        String nextTxHash = "0xtest";

        TestableHandler(BridgeConfig config) {
            super(config);
        }

        @Override
        public String getChainId() {
            return "test";
        }

        @Override
        protected String submitLockTransaction(LockRequest request) {
            return nextTxHash;
        }

        @Override
        protected String submitMintTransaction(MintRequest request, BridgeTransaction lockTx) {
            return nextTxHash;
        }

        @Override
        protected String submitBurnTransaction(BurnRequest request) {
            return nextTxHash;
        }

        @Override
        protected String submitUnlockTransaction(UnlockRequest request, BridgeTransaction burnTx) {
            return nextTxHash;
        }

        @Override
        public int queryTransactionStatus(String txHash) {
            return 12;
        }
    }
}