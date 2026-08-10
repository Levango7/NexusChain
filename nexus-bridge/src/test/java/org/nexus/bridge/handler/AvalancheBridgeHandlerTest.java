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
import org.nexus.bridge.model.BridgeTransaction;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AvalancheBridgeHandler} 单元测试：覆盖 4 种桥操作（lock/mint/burn/unlock）状态机、
 * 参数校验、RPC 未配置错误、推荐确认数与配置注入。
 *
 * <p>Avalanche C-Chain 兼容 EVM，复用 {@link AbstractBridgeHandler} 模板方法，
 * 本测试重点验证 Avalanche 特有的链 ID（{@code "avalanche"}）、推荐确认数（20）
 * 与 4 种操作的完整状态转换。</p>
 *
 * <h2>测试策略</h2>
 * <p>成功路径测试通过反射注入 mock Web3j（与 {@code AbstractEvmChainAdapterTest.TestableAdapter} 一致），
 * mock {@code eth_call} 返回非 null 响应，使 {@code submitContractCall} 走通成功分支。
 * 校验失败与 RPC 未配置路径无需 mock，直接断言异常错误码。</p>
 */
@ExtendWith(MockitoExtension.class)
class AvalancheBridgeHandlerTest {

    /** EVM 地址（20 字节 hex）。 */
    private static final String EVM_USER = "0x1234567890abcdef1234567890abcdef12345678";

    /** EVM 目标地址。 */
    private static final String EVM_TARGET = "0xabcdef1234567890abcdef1234567890abcdef12";

    /** 32 字节 hex 哈希（用作 lockTxId / burnTxId）。 */
    private static final String TX_ID_HEX = "0x" + "a".repeat(64);

    /** Avalanche C-Chain RPC 端点（Fuji 测试网，用于测试）。 */
    private static final String RPC_ENDPOINT = "https://api.avax-test.network/ext/bc/C/rpc";

    /** 桥合约地址。 */
    private static final String CONTRACT_ADDRESS = "0xContract";

    @Mock
    private Web3j web3j;

    @Mock
    private Request<?, EthCall> ethCallRequest;

    private BridgeConfig config;

    @BeforeEach
    void setUp() {
        config = new BridgeConfig();
        config.setSignatureThreshold(2);
        config.setMaxAmountPerTx(10_000_000_000L);
        config.setDailyLimit(100_000_000_000L);
        config.setValidatorPublicKeys(Arrays.asList("v1", "v2", "v3"));
    }

    // ==================== 链 ID 测试 ====================

    @Test
    @DisplayName("单参数构造应返回链 ID avalanche")
    void singleArgConstructor_returnsAvalancheChainId() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        assertEquals("avalanche", handler.getChainId());
    }

    @Test
    @DisplayName("全参数构造应正确设置合约地址与 RPC 端点")
    void fullConstructor_setsFields() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(
                config, CONTRACT_ADDRESS, "0xToken", RPC_ENDPOINT);
        assertEquals("avalanche", handler.getChainId());
        assertEquals(CONTRACT_ADDRESS, handler.getContractAddress());
        assertEquals("0xToken", handler.getCpayTokenAddress());
        assertEquals(RPC_ENDPOINT, handler.getRpcEndpoint());
    }

    // ==================== 推荐确认数测试 ====================

    @Test
    @DisplayName("推荐确认数应为 20（C-Chain Snowman 共识）")
    void recommendedConfirmations_is20() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        assertEquals(20, handler.getRecommendedConfirmations());
    }

    // ==================== lock 状态机测试 ====================

    @Test
    @DisplayName("lock: 成功时返回 LOCK_PENDING 状态的桥交易")
    void lock_success() throws IOException {
        AvalancheBridgeHandler handler = createHandlerWithMockWeb3j();
        LockRequest req = new LockRequest("avalanche", "ethereum", 1000L,
                EVM_USER, EVM_TARGET, "0xsrcHash");

        BridgeTransaction tx = handler.lock(req);

        assertNotNull(tx);
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK, tx.getOperationType());
        assertEquals(BridgeTransaction.BridgeTxStatus.LOCK_PENDING, tx.getStatus());
        assertEquals(1000L, tx.getAmount());
        assertEquals("avalanche", tx.getSourceChainId());
        assertEquals("ethereum", tx.getTargetChainId());
        assertNotNull(tx.getSourceTxHash());
        assertEquals(EVM_USER, tx.getUserAddress());
        assertEquals(EVM_TARGET, tx.getTargetAddress());
    }

    @Test
    @DisplayName("lock: null 请求抛 INVALID_REQUEST")
    void lock_nullRequest() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 金额 0 抛 INVALID_AMOUNT")
    void lock_zeroAmount() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", 0,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 负金额抛 INVALID_AMOUNT")
    void lock_negativeAmount() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", -100,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 空用户地址抛 INVALID_ADDRESS")
    void lock_emptyUserAddress() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", 1000,
                "", EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 空目标地址抛 INVALID_ADDRESS")
    void lock_emptyTargetAddress() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", 1000,
                EVM_USER, "", "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 超过单笔上限抛 AMOUNT_EXCEEDS_LIMIT")
    void lock_exceedsLimit() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", 20_000_000_000L,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: 金额校验失败应在 RPC 校验前抛出")
    void lock_amountValidationBeforeRpcCheck() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", -1,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: RPC 未配置时校验通过后应抛 RPC_NOT_CONFIGURED")
    void lock_rpcNotConfigured() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        LockRequest req = new LockRequest("avalanche", "ethereum", 1000,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    @Test
    @DisplayName("lock: eth_call 返回 null 抛 CONTRACT_CALL_REJECTED")
    void lock_contractCallRejected() throws IOException {
        AvalancheBridgeHandler handler = createHandlerWithMockWeb3jReturningNull();
        LockRequest req = new LockRequest("avalanche", "ethereum", 1000,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.lock(req));
        assertEquals("CONTRACT_CALL_REJECTED", ex.getErrorCode());
    }

    // ==================== mint 状态机测试 ====================

    @Test
    @DisplayName("mint: 成功时将 lockTx 状态更新为 MINT_PENDING")
    void mint_success() throws IOException {
        AvalancheBridgeHandler handler = createHandlerWithMockWeb3j();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "0xMinter", "avalanche");

        BridgeTransaction lockTx = createLockTx();
        BridgeTransaction result = handler.mint(req, lockTx);

        assertNotNull(result);
        assertEquals(BridgeTransaction.BridgeTxStatus.MINT_PENDING, result.getStatus());
        assertNotNull(result.getTargetTxHash());
        // 原始字段应保留
        assertEquals("avalanche", result.getSourceChainId());
        assertEquals("ethereum", result.getTargetChainId());
        assertEquals(1000L, result.getAmount());
    }

    @Test
    @DisplayName("mint: null 请求抛 INVALID_REQUEST")
    void mint_nullRequest() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BridgeTransaction lockTx = createLockTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(null, lockTx));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: null lockTx 抛 INVALID_REQUEST")
    void mint_nullLockTx() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "0xMinter", "avalanche");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: 签名不足抛 INSUFFICIENT_SIGNATURES")
    void mint_insufficientSignatures() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "0xMinter", "avalanche");

        BridgeTransaction lockTx = createLockTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: lockTx 状态非 LOCKED 抛 INVALID_LOCK_STATE")
    void mint_invalidLockState() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "0xMinter", "avalanche");

        BridgeTransaction lockTx = createLockTx();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.LOCK_PENDING);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INVALID_LOCK_STATE", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: lockTx 状态为 MINTED 抛 INVALID_LOCK_STATE")
    void mint_alreadyMinted() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "0xMinter", "avalanche");

        BridgeTransaction lockTx = createLockTx();
        lockTx.setStatus(BridgeTransaction.BridgeTxStatus.MINTED);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("INVALID_LOCK_STATE", ex.getErrorCode());
    }

    @Test
    @DisplayName("mint: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void mint_rpcNotConfigured() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        MintRequest req = new MintRequest(TX_ID_HEX, sigs, "0xMinter", "avalanche");
        BridgeTransaction lockTx = createLockTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.mint(req, lockTx));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== burn 状态机测试 ====================

    @Test
    @DisplayName("burn: 成功时返回 BURN_PENDING 状态的桥交易")
    void burn_success() throws IOException {
        AvalancheBridgeHandler handler = createHandlerWithMockWeb3j();
        BurnRequest req = new BurnRequest("ethereum", "avalanche", 1000L,
                EVM_USER, EVM_TARGET, "0xsrcHash");

        BridgeTransaction tx = handler.burn(req);

        assertNotNull(tx);
        assertEquals(BridgeTransaction.BridgeOperationType.BRIDGE_BURN, tx.getOperationType());
        assertEquals(BridgeTransaction.BridgeTxStatus.BURN_PENDING, tx.getStatus());
        assertEquals(1000L, tx.getAmount());
        assertEquals("ethereum", tx.getSourceChainId());
        assertEquals("avalanche", tx.getTargetChainId());
        assertNotNull(tx.getSourceTxHash());
    }

    @Test
    @DisplayName("burn: null 请求抛 INVALID_REQUEST")
    void burn_nullRequest() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 金额 0 抛 INVALID_AMOUNT")
    void burn_zeroAmount() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BurnRequest req = new BurnRequest("ethereum", "avalanche", 0,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 负金额抛 INVALID_AMOUNT")
    void burn_negativeAmount() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BurnRequest req = new BurnRequest("ethereum", "avalanche", -100,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_AMOUNT", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 空用户地址抛 INVALID_ADDRESS")
    void burn_emptyUserAddress() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BurnRequest req = new BurnRequest("ethereum", "avalanche", 1000,
                "", EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("INVALID_ADDRESS", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: 超过单笔上限抛 AMOUNT_EXCEEDS_LIMIT")
    void burn_exceedsLimit() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BurnRequest req = new BurnRequest("ethereum", "avalanche", 20_000_000_000L,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("AMOUNT_EXCEEDS_LIMIT", ex.getErrorCode());
    }

    @Test
    @DisplayName("burn: RPC 未配置时校验通过后应抛 RPC_NOT_CONFIGURED")
    void burn_rpcNotConfigured() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BurnRequest req = new BurnRequest("ethereum", "avalanche", 1000,
                EVM_USER, EVM_TARGET, "0xsrcHash");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.burn(req));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== unlock 状态机测试 ====================

    @Test
    @DisplayName("unlock: 成功时将 burnTx 状态更新为 UNLOCK_PENDING")
    void unlock_success() throws IOException {
        AvalancheBridgeHandler handler = createHandlerWithMockWeb3j();
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "0xUnlocker", "avalanche");

        BridgeTransaction burnTx = createBurnTx();
        BridgeTransaction result = handler.unlock(req, burnTx);

        assertNotNull(result);
        assertEquals(BridgeTransaction.BridgeTxStatus.UNLOCK_PENDING, result.getStatus());
        assertNotNull(result.getTargetTxHash());
        // 原始字段应保留
        assertEquals("avalanche", result.getSourceChainId());
        assertEquals("ethereum", result.getTargetChainId());
        assertEquals(1000L, result.getAmount());
    }

    @Test
    @DisplayName("unlock: null 请求抛 INVALID_REQUEST")
    void unlock_nullRequest() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BridgeTransaction burnTx = createBurnTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(null, burnTx));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: null burnTx 抛 INVALID_REQUEST")
    void unlock_nullBurnTx() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "0xUnlocker", "avalanche");
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, null));
        assertEquals("INVALID_REQUEST", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: 签名不足抛 INSUFFICIENT_SIGNATURES")
    void unlock_insufficientSignatures() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "0xUnlocker", "avalanche");

        BridgeTransaction burnTx = createBurnTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INSUFFICIENT_SIGNATURES", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: burnTx 状态非 BURNED 抛 INVALID_BURN_STATE")
    void unlock_invalidBurnState() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "0xUnlocker", "avalanche");

        BridgeTransaction burnTx = createBurnTx();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.BURN_PENDING);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INVALID_BURN_STATE", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: burnTx 状态为 UNLOCKED 抛 INVALID_BURN_STATE")
    void unlock_alreadyUnlocked() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "0xUnlocker", "avalanche");

        BridgeTransaction burnTx = createBurnTx();
        burnTx.setStatus(BridgeTransaction.BridgeTxStatus.UNLOCKED);
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("INVALID_BURN_STATE", ex.getErrorCode());
    }

    @Test
    @DisplayName("unlock: RPC 未配置抛 RPC_NOT_CONFIGURED")
    void unlock_rpcNotConfigured() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        Map<String, String> sigs = new HashMap<>();
        sigs.put("v1", "sig1");
        sigs.put("v2", "sig2");
        UnlockRequest req = new UnlockRequest(TX_ID_HEX, sigs, "0xUnlocker", "avalanche");
        BridgeTransaction burnTx = createBurnTx();
        BridgeException ex = assertThrows(BridgeException.class, () -> handler.unlock(req, burnTx));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== queryTransactionStatus 测试 ====================

    @Test
    @DisplayName("queryTransactionStatus: RPC 未配置应抛 BridgeException")
    void queryTransactionStatus_rpcNotConfiguredThrows() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BridgeException ex = assertThrows(BridgeException.class,
                () -> handler.queryTransactionStatus("0xhash"));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== awaitConfirmations 测试 ====================

    @Test
    @DisplayName("awaitConfirmations: RPC 未配置应抛 BridgeException")
    void awaitConfirmations_rpcNotConfiguredThrows() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        BridgeException ex = assertThrows(BridgeException.class,
                () -> handler.awaitConfirmations("0xhash"));
        assertEquals("RPC_NOT_CONFIGURED", ex.getErrorCode());
    }

    // ==================== Setter / Getter 测试 ====================

    @Test
    @DisplayName("setter 应正确更新合约地址与 RPC 端点")
    void setters_updateFields() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(config);
        handler.setContractAddress("0xNewContract");
        handler.setCpayTokenAddress("0xNewToken");
        handler.setRpcEndpoint(RPC_ENDPOINT);

        assertEquals("0xNewContract", handler.getContractAddress());
        assertEquals("0xNewToken", handler.getCpayTokenAddress());
        assertEquals(RPC_ENDPOINT, handler.getRpcEndpoint());
    }

    @Test
    @DisplayName("setRpcEndpoint 后应重置 Web3j 客户端")
    void setRpcEndpoint_resetsWeb3j() {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(
                config, CONTRACT_ADDRESS, "0xToken", RPC_ENDPOINT);
        // 修改 RPC 端点
        handler.setRpcEndpoint("http://new-rpc:9650/ext/bc/C/rpc");
        assertEquals("http://new-rpc:9650/ext/bc/C/rpc", handler.getRpcEndpoint());
        // 再次设置应仍可正常工作
        handler.setRpcEndpoint("http://another-rpc:9650/ext/bc/C/rpc");
        assertEquals("http://another-rpc:9650/ext/bc/C/rpc", handler.getRpcEndpoint());
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建一个 LOCKED 状态的桥交易（用于 mint 测试）。
     *
     * <p>模拟正向跨链中 lock 操作已完成确认、状态转为 LOCKED 的场景。</p>
     *
     * @return 桥交易
     */
    private BridgeTransaction createLockTx() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("lock-tx-id");
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_LOCK);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.LOCKED);
        tx.setSourceChainId("avalanche");
        tx.setTargetChainId("ethereum");
        tx.setAmount(1000L);
        tx.setUserAddress(EVM_USER);
        tx.setTargetAddress(EVM_TARGET);
        tx.setSourceTxHash("0xsrcHash");
        return tx;
    }

    /**
     * 创建一个 BURNED 状态的桥交易（用于 unlock 测试）。
     *
     * <p>模拟反向跨链中 burn 操作已完成确认、状态转为 BURNED 的场景。</p>
     *
     * @return 桥交易
     */
    private BridgeTransaction createBurnTx() {
        BridgeTransaction tx = new BridgeTransaction();
        tx.setTxId("burn-tx-id");
        tx.setOperationType(BridgeTransaction.BridgeOperationType.BRIDGE_BURN);
        tx.setStatus(BridgeTransaction.BridgeTxStatus.BURNED);
        tx.setSourceChainId("avalanche");
        tx.setTargetChainId("ethereum");
        tx.setAmount(1000L);
        tx.setUserAddress(EVM_USER);
        tx.setTargetAddress(EVM_TARGET);
        tx.setSourceTxHash("0xsrcHash");
        return tx;
    }

    /**
     * 创建一个注入了 mock Web3j 的 AvalancheBridgeHandler，
     * mock {@code eth_call} 返回非 null 响应（"0xresult"），
     * 使 {@code submitContractCall} 走通成功分支。
     *
     * <p>通过反射将 mock Web3j 注入到 handler 的私有 {@code web3j} 字段，
     * 与 {@code AbstractEvmChainAdapterTest.TestableAdapter} 模式一致。</p>
     *
     * @return 配置好 mock 的 handler
     * @throws IOException 如果 mock 设置失败
     */
    private AvalancheBridgeHandler createHandlerWithMockWeb3j() throws IOException {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(
                config, CONTRACT_ADDRESS, "0xToken", RPC_ENDPOINT);
        injectMockWeb3j(handler);
        // mock eth_call 返回成功响应
        EthCall response = mock(EthCall.class);
        lenient().when(response.hasError()).thenReturn(false);
        lenient().when(response.getValue()).thenReturn("0xresult");
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        lenient().when(ethCallRequest.send()).thenReturn(response);
        return handler;
    }

    /**
     * 创建一个注入了 mock Web3j 的 AvalancheBridgeHandler，
     * mock {@code eth_call} 返回 null 响应（模拟合约调用被拒绝），
     * 使 {@code submitContractCall} 抛 {@code CONTRACT_CALL_REJECTED}。
     *
     * @return 配置好 mock 的 handler
     * @throws IOException 如果 mock 设置失败
     */
    private AvalancheBridgeHandler createHandlerWithMockWeb3jReturningNull() throws IOException {
        AvalancheBridgeHandler handler = new AvalancheBridgeHandler(
                config, CONTRACT_ADDRESS, "0xToken", RPC_ENDPOINT);
        injectMockWeb3j(handler);
        // mock eth_call 返回错误响应（executeViewCall 返回 null）
        EthCall response = mock(EthCall.class);
        lenient().when(response.hasError()).thenReturn(true);
        org.web3j.protocol.core.Response.Error error = mock(org.web3j.protocol.core.Response.Error.class);
        lenient().when(error.getCode()).thenReturn(-1);
        lenient().when(error.getMessage()).thenReturn("revert");
        lenient().when(response.getError()).thenReturn(error);
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        lenient().when(ethCallRequest.send()).thenReturn(response);
        return handler;
    }

    /**
     * 通过反射将 mock Web3j 注入到 handler 的私有 {@code web3j} 字段。
     *
     * @param handler 目标 handler
     */
    private void injectMockWeb3j(AvalancheBridgeHandler handler) {
        try {
            java.lang.reflect.Field field = AvalancheBridgeHandler.class.getDeclaredField("web3j");
            field.setAccessible(true);
            field.set(handler, web3j);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject mock Web3j", e);
        }
    }
}
