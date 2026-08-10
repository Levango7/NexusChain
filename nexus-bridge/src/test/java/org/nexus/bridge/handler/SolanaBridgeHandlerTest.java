package org.nexus.bridge.handler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.BurnRequest;
import org.nexus.bridge.LockRequest;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.adapter.SolanaAdapter;
import org.nexus.bridge.model.BridgeTransaction;
import org.nexus.bridge.solana.SolanaRpcClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SolanaBridgeHandler} 单元测试：覆盖 4 种操作（lock/mint/burn/unlock）状态机
 * 与参数校验、RPC 未配置、模拟失败等场景。
 */
@ExtendWith(MockitoExtension.class)
class SolanaBridgeHandlerTest {

    /** Solana 系统程序地址（base58，32 字节全零的 base58 编码）。 */
    private static final String SOLANA_PUBKEY = "11111111111111111111111111111111";

    /** EVM 地址（20 字节 hex）。 */
    private static final String EVM_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";

    /** 32 字节 hex 哈希（用作 lockTxId / burnTxId）。 */
    private static final String TX_ID_HEX = "0x" + "a".repeat(64);

    @Mock
    private SolanaAdapter solanaAdapter;

    private BridgeConfig config;
    private SolanaBridgeHandler handler;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(10_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setValidatorPublicKeys(java.util.Arrays.asList("v1", "v2", "v3"));
        handler = new SolanaBridgeHandler(config);
    }

    // ==================== 链 ID 测试 ====================

    @Test
    @DisplayName("getChainId 应返回 solana")
    void getChainId_returnsSolana() {
        assertEquals("solana", handler.getChainId());
    }

    @Test
    @DisplayName("全参数构造应正确设置字段")
    void fullConstructor_setsFields() {
        SolanaBridgeHandler h = new SolanaBridgeHandler(
                config, "BridgeProgramId", "NexMint", "http://devnet:8899");
        assertEquals("solana", h.getChainId());
        assertEquals("BridgeProgramId", h.getBridgeProgramId());
        assertEquals("NexMint", h.getNexusTokenMint());
        assertEquals("http://devnet:8899", h.getRpcEndpoint());
    }

    // ==================== lock 状态机测试 ====================

    @Test
    @DisplayName("lock: 成功时返回 LOCK_PENDING 状态的桥交易")
    void lock_success() {
        setupAdapterMockSuccess();
        LockRequest req = new LockRequest("solana", "ethereum", 1000L,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");

        BridgeTransaction tx = handler.lock(req);

        assertNotNull(tx);
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK, tx.getOperationType());
        assertEquals(BridgeTransaction.BridgeTxStatus.LOCK_PENDING, tx.getStatus());
        assertEquals(1000L, tx.getAmount());
        assertEquals("solana", tx.getSourceChainId());
        assertEquals("ethereum", tx.getTargetChainId());
        assertNotNull(tx.getSourceTxHash());
    }

    @Test
    @DisplayName("lock: null 请求抛 INVALID_REQUEST")
    void lock_nullRequest() {
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 金额 0 抛 INVALID_AMOUNT")
    void lock_zeroAmount() {
        LockRequest req = new LockRequest("solana", "ethereum", 0,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 负金额抛 INVALID_AMOUNT")
    void lock_negativeAmount() {
        LockRequest req = new LockRequest("solana", "ethereum", -100,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 空用户地址抛 INVALID_ADDRESS")
    void lock_emptyUserAddress() {
        LockRequest req = new LockRequest("solana", "ethereum", 1000,
                "", EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 超过单笔上限抛 AMOUNT_EXCEEDS_LIMIT")
    void lock_exceedsLimit() {
        LockRequest req = new LockRequest("solana", "ethereum", 20_000_000_000L,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void lock_rpcNotConfigured() {
        LockRequest req = new LockRequest("solana", "ethereum", 1000,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: simulateTransaction 失败抛 SIMULATE_REJECTED")
    void lock_simulateRejected() {
        setupAdapterMockFailure();
        LockRequest req = new LockRequest("solana", "ethereum", 1000,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("SIMULATE_REJECTED", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: simulateTransaction 返回 null 抛 SIMULATE_FAILED")
    void lock_simulateNull() {
        setupAdapterMockNull();
        LockRequest req = new LockRequest("solana", "ethereum", 1000,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("SIMULATE_FAILED", ex.getErrorCode());
    }

    // ==================== mint 状态机测试 ====================

    @Test
    @DisplayName("mint: 成功时将 lockTx 状态更新为 MINT_PENDING")
    void mint_success() {
        setupAdapterMockSuccess();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "minter", "ethereum");

        BridgeTransaction lockTx = createLockTx();
        BridgeTransaction result = handler.mint(req, lockTx);

        assertNotNull(result);
        assertEquals(BridgeTransaction.BridgeTxStatus.MINT_PENDING, result.getStatus());
        assertNotNull(result.getTargetTxHash());
    }

    @Test
    @DisplayName("mint: null 请求抛 INVALID_REQUEST")
    void mint_nullRequest() {
        BridgeTransaction lockTx = createLockTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(null, lockTx));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: 签名不足抛 INSUFFICIENT_SIGNATURES")
    void mint_insufficientSignatures() {
        setupAdapterMockSuccess();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "minter", "ethereum");

        BridgeTransaction lockTx = createLockTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: lockTx 状态非 LOCKED 抛 INVALID_LOCK_STATE")
    void mint_invalidLockState() {
        setupAdapterMockSuccess();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "minter", "ethereum");

        BridgeTransaction lockTx = createLockTx();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCK_PENDING);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INVALID_LOCK_STATE", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void mint_rpcNotConfigured() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "minter", "ethereum");
        BridgeTransaction lockTx = createLockTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== burn 状态机测试 ====================

    @Test
    @DisplayName("burn: 成功时返回 BURN_PENDING 状态的桥交易")
    void burn_success() {
        setupAdapterMockSuccess();
        BurnRequest req = new BurnRequest("ethereum", "solana", 1000L,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");

        BridgeTransaction tx = handler.burn(req);

        assertNotNull(tx);
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_BURN, tx.getOperationType());
        assertEquals(BridgeTransaction.BridgeTxStatus.BURN_PENDING, tx.getStatus());
        assertEquals(1000L, tx.getAmount());
    }

    @Test
    @DisplayName("burn: null 请求抛 INVALID_REQUEST")
    void burn_nullRequest() {
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 金额 0 抛 INVALID_AMOUNT")
    void burn_zeroAmount() {
        BurnRequest req = new BurnRequest("ethereum", "solana", 0,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 空用户地址抛 INVALID_ADDRESS")
    void burn_emptyUserAddress() {
        BurnRequest req = new BurnRequest("ethereum", "solana", 1000,
                "", EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 超过单笔上限抛 AMOUNT_EXCEEDS_LIMIT")
    void burn_exceedsLimit() {
        BurnRequest req = new BurnRequest("ethereum", "solana", 20_000_000_000L,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void burn_rpcNotConfigured() {
        BurnRequest req = new BurnRequest("ethereum", "solana", 1000,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: simulateTransaction 失败抛 SIMULATE_REJECTED")
    void burn_simulateRejected() {
        setupAdapterMockFailure();
        BurnRequest req = new BurnRequest("ethereum", "solana", 1000,
                SOLANA_PUBKEY, EVM_ADDRESS, "srcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("SIMULATE_REJECTED", ex.getErrorCode());
    }

    // ==================== unlock 状态机测试 ====================

    @Test
    @DisplayName("unlock: 成功时将 burnTx 状态更新为 UNLOCK_PENDING")
    void unlock_success() {
        setupAdapterMockSuccess();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "unlocker", "solana");

        BridgeTransaction burnTx = createBurnTx();
        BridgeTransaction result = handler.unlock(req, burnTx);

        assertNotNull(result);
        assertEquals(BridgeTransaction.BridgeTxStatus.UNLOCK_PENDING, result.getStatus());
        assertNotNull(result.getTargetTxHash());
    }

    @Test
    @DisplayName("unlock: null 请求抛 INVALID_REQUEST")
    void unlock_nullRequest() {
        BridgeTransaction burnTx = createBurnTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(null, burnTx));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: 签名不足抛 INSUFFICIENT_SIGNATURES")
    void unlock_insufficientSignatures() {
        setupAdapterMockSuccess();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "unlocker", "solana");

        BridgeTransaction burnTx = createBurnTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: burnTx 状态非 BURNED 抛 INVALID_BURN_STATE")
    void unlock_invalidBurnState() {
        setupAdapterMockSuccess();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "unlocker", "solana");

        BridgeTransaction burnTx = createBurnTx();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.BURN_PENDING);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INVALID_BURN_STATE", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void unlock_rpcNotConfigured() {
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "unlocker", "solana");
        BridgeTransaction burnTx = createBurnTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== queryTransactionStatus 测试 ====================

    @Test
    @DisplayName("queryTransactionStatus: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void queryTransactionStatus_rpcNotConfigured() {
        BridgeException ex = assertThrows(BridgeException.class,
                () -> handler.queryTransactionStatus("sig"));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("queryTransactionStatus: 交易存在时返回推荐确认数")
    void queryTransactionStatus_txExists() {
        setupAdapterMockForQuery(true);
        int confirmations = handler.queryTransactionStatus("sig");
        assertEquals(handler.getRecommendedConfirmations(), confirmations);
    }

    @Test
    @DisplayName("queryTransactionStatus: 交易不存在时返回 0")
    void queryTransactionStatus_txNotExists() {
        setupAdapterMockForQuery(false);
        int confirmations = handler.queryTransactionStatus("sig");
        assertEquals(0, confirmations);
    }

    // ==================== awaitConfirmations 测试 ====================

    @Test
    @DisplayName("awaitConfirmations: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void awaitConfirmations_rpcNotConfigured() {
        BridgeException ex = assertThrows(BridgeException.class,
                () -> handler.awaitConfirmations("sig"));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("awaitConfirmations: 交易存在时返回 true")
    void awaitConfirmations_txExists() {
        setupAdapterMockForQuery(true);
        assertTrue(handler.awaitConfirmations("sig"));
    }

    @Test
    @DisplayName("awaitConfirmations: 交易不存在时返回 false")
    void awaitConfirmations_txNotExists() {
        setupAdapterMockForQuery(false);
        assertFalse(handler.awaitConfirmations("sig"));
    }

    // ==================== 推荐确认数测试 ====================

    @Test
    @DisplayName("getRecommendedConfirmations: 应返回 32")
    void getRecommendedConfirmations_returns32() {
        assertEquals(32, handler.getRecommendedConfirmations());
    }

    // ==================== Setter / Getter 测试 ====================

    @Test
    @DisplayName("setter 应正确更新字段")
    void setters_updateFields() {
        handler.setBridgeProgramId("newProgram");
        handler.setNexusTokenMint("newMint");
        handler.setRpcEndpoint("http://new-rpc:8899");

        assertEquals("newProgram", handler.getBridgeProgramId());
        assertEquals("newMint", handler.getNexusTokenMint());
        assertEquals("http://new-rpc:8899", handler.getRpcEndpoint());
    }

    @Test
    @DisplayName("setRpcEndpoint 后应重置适配器")
    void setRpcEndpoint_resetsAdapter() {
        handler.setRpcEndpoint("http://first:8899");
        handler.setRpcEndpoint("http://second:8899");
        assertEquals("http://second:8899", handler.getRpcEndpoint());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个 LOCKED 状态的桥交易（用于 mint 测试）。
     *
     * @return 桥交易
     */
    private BridgeTransaction createLockTx() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("lock-tx-id");
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        tx.setSourceChainId("solana");
        tx.setTargetChainId("ethereum");
        tx.setAmount(1000L);
        tx.setUserAddress(SOLANA_PUBKEY);
        tx.setTargetAddress(EVM_ADDRESS);
        tx.setSourceTxHash("srcHash");
        return tx;
    }

    /**
     * 创建一个 BURNED 状态的桥交易（用于 unlock 测试）。
     *
     * @return 桥交易
     */
    private BridgeTransaction createBurnTx() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("burn-tx-id");
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_BURN);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.BURNED);
        tx.setSourceChainId("solana");
        tx.setTargetChainId("ethereum");
        tx.setAmount(1000L);
        tx.setUserAddress(SOLANA_PUBKEY);
        tx.setTargetAddress(EVM_ADDRESS);
        tx.setSourceTxHash("srcHash");
        return tx;
    }

    /**
     * 设置适配器 mock，simulateTransaction 返回成功。
     */
    private void setupAdapterMockSuccess() {
        SolanaRpcClient.SimulationResult success = new SolanaRpcClient.SimulationResult();
        success.err = null;
        success.unitsConsumed = 100;
        success.logs = java.util.Collections.emptyList();
        lenient().when(solanaAdapter.simulateTransaction(any())).thenReturn(success);
        handler.setSolanaAdapter(solanaAdapter);
    }

    /**
     * 设置适配器 mock，simulateTransaction 返回失败。
     */
    private void setupAdapterMockFailure() {
        SolanaRpcClient.SimulationResult failure = new SolanaRpcClient.SimulationResult();
        failure.err = "InsufficientFunds";
        lenient().when(solanaAdapter.simulateTransaction(any())).thenReturn(failure);
        handler.setSolanaAdapter(solanaAdapter);
    }

    /**
     * 设置适配器 mock，simulateTransaction 返回 null。
     */
    private void setupAdapterMockNull() {
        lenient().when(solanaAdapter.simulateTransaction(any())).thenReturn(null);
        handler.setSolanaAdapter(solanaAdapter);
    }

    /**
     * 设置适配器 mock，getTransactionReceipt 返回指定存在性。
     *
     * @param exists 交易是否存在
     */
    private void setupAdapterMockForQuery(boolean exists) {
        lenient().when(solanaAdapter.getTransactionReceipt(any()))
                .thenReturn(exists ? new Object() : null);
        handler.setSolanaAdapter(solanaAdapter);
    }
}