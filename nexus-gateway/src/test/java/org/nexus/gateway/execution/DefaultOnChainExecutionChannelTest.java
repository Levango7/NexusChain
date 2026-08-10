package org.nexus.gateway.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultOnChainExecutionChannel} 单元测试：覆盖 sandbox/生产模式、
 * 幂等、参数校验、queryStatus 各分支。
 */
@ExtendWith(MockitoExtension.class)
class DefaultOnChainExecutionChannelTest {

    @Mock private ChainRpcClient chainRpcClient;
    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WalletMgmtFeignClient walletMgmtClient;

    private GatewayConfig cfg;

    @BeforeEach
    void setUp() {
        cfg = new GatewayConfig();
        cfg.getChain().setConfirmations(12);
    }

    private DefaultOnChainExecutionChannel newChannel() {
        return new DefaultOnChainExecutionChannel(chainRpcClient,
                signingServiceClient, walletMgmtClient, cfg);
    }

    private TransactionRequest validRequest(String requestId) {
        return new TransactionRequest(
                TransactionRequest.Type.SETTLEMENT,
                "from-addr",
                "to-addr",
                new BigDecimal("100"),
                "NEX",
                "memo",
                requestId);
    }

    // === 参数校验 ===

    @Test
    @DisplayName("execute: null request 抛异常")
    void execute_nullRequest() {
        assertThrows(IllegalArgumentException.class, () -> newChannel().execute(null));
    }

    @Test
    @DisplayName("execute: type 为 null 抛异常")
    void execute_nullType() {
        TransactionRequest req = validRequest("r-1");
        setField(req, "type", null);
        assertThrows(IllegalArgumentException.class, () -> newChannel().execute(req));
    }

    @Test
    @DisplayName("execute: fromAddress 为空抛异常")
    void execute_emptyFrom() {
        TransactionRequest req = validRequest("r-2");
        setField(req, "fromAddress", "");
        assertThrows(IllegalArgumentException.class, () -> newChannel().execute(req));
    }

    @Test
    @DisplayName("execute: toAddress 为空抛异常")
    void execute_emptyTo() {
        TransactionRequest req = validRequest("r-3");
        setField(req, "toAddress", "");
        assertThrows(IllegalArgumentException.class, () -> newChannel().execute(req));
    }

    @Test
    @DisplayName("execute: amount 非正抛异常")
    void execute_nonPositiveAmount() {
        TransactionRequest req = validRequest("r-4");
        setField(req, "amount", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> newChannel().execute(req));
    }

    // === sandbox 模式 ===

    @Test
    @DisplayName("execute: platformPubkey 未配置 -> sandbox 模式，返回 SIMULATED-")
    void execute_sandbox_pubkeyMissing() {
        cfg.getExchangeWallet().setPlatformPubkey("");
        TransactionResult result = newChannel().execute(validRequest("r-5"));
        assertTrue(result.isSuccess());
        assertTrue(result.isSimulated());
        assertTrue(result.getTxHash().startsWith("SIMULATED-"));
    }

    @Test
    @DisplayName("execute: skipConfirmation=true -> sandbox 模式")
    void execute_sandbox_skipConfirm() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        cfg.getChain().setSkipConfirmation(true);
        TransactionResult result = newChannel().execute(validRequest("r-6"));
        assertTrue(result.isSuccess());
        assertTrue(result.isSimulated());
    }

    @Test
    @DisplayName("execute: 幂等 - 相同 requestId 二次调用返回缓存")
    void execute_idempotent() {
        cfg.getExchangeWallet().setPlatformPubkey("");
        DefaultOnChainExecutionChannel channel = newChannel();
        TransactionRequest req = validRequest("idem-1");

        TransactionResult r1 = channel.execute(req);
        TransactionResult r2 = channel.execute(req);
        assertEquals(r1.getTxHash(), r2.getTxHash());
    }

    @Test
    @DisplayName("execute: requestId 为 null 不缓存但正常执行")
    void execute_nullRequestId() {
        cfg.getExchangeWallet().setPlatformPubkey("");
        TransactionResult result = newChannel().execute(validRequest(null));
        assertTrue(result.isSuccess());
    }

    // === 生产模式 ===

    @Test
    @DisplayName("execute: 生产模式 + 签名成功 + 链确认 -> SUCCESS")
    void execute_production_confirmed() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        when(walletMgmtClient.addressToPubkeyHash("to-addr")).thenReturn("to-hash");
        when(signingServiceClient.signTransfer("platform-pk", "to-hash", new BigDecimal("100")))
                .thenReturn("0xTxHash");
        when(chainRpcClient.isTransactionConfirmed("0xTxHash")).thenReturn(true);

        TransactionResult result = newChannel().execute(validRequest("r-7"));
        assertTrue(result.isSuccess());
        assertFalse(result.isSimulated());
        assertEquals("0xTxHash", result.getTxHash());
    }

    @Test
    @DisplayName("execute: 生产模式 + 签名成功 + 链未确认 -> PENDING")
    void execute_production_pending() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        when(walletMgmtClient.addressToPubkeyHash("to-addr")).thenReturn("to-hash");
        when(signingServiceClient.signTransfer("platform-pk", "to-hash", new BigDecimal("100")))
                .thenReturn("0xTxHash");
        when(chainRpcClient.isTransactionConfirmed("0xTxHash")).thenReturn(false);

        TransactionResult result = newChannel().execute(validRequest("r-8"));
        assertFalse(result.isSuccess());
        assertEquals("0xTxHash", result.getTxHash());
    }

    @Test
    @DisplayName("execute: 生产模式 + addressToPubkeyHash 返回 null -> FAILED")
    void execute_production_addrNull() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        when(walletMgmtClient.addressToPubkeyHash("to-addr")).thenReturn(null);

        TransactionResult result = newChannel().execute(validRequest("r-9"));
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("execute: 生产模式 + signTransfer 抛异常 -> FAILED")
    void execute_production_signException() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        when(walletMgmtClient.addressToPubkeyHash("to-addr")).thenReturn("to-hash");
        when(signingServiceClient.signTransfer(any(), any(), any())).thenThrow(new RuntimeException("sign err"));

        TransactionResult result = newChannel().execute(validRequest("r-10"));
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("execute: 生产模式 + signTransfer 返回 null -> FAILED")
    void execute_production_signNull() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        when(walletMgmtClient.addressToPubkeyHash("to-addr")).thenReturn("to-hash");
        when(signingServiceClient.signTransfer(any(), any(), any())).thenReturn(null);

        TransactionResult result = newChannel().execute(validRequest("r-11"));
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("execute: 生产模式 + 链确认查询抛异常 -> PENDING")
    void execute_production_confirmException() {
        cfg.getExchangeWallet().setPlatformPubkey("platform-pk");
        when(walletMgmtClient.addressToPubkeyHash("to-addr")).thenReturn("to-hash");
        when(signingServiceClient.signTransfer(any(), any(), any())).thenReturn("0xTxHash");
        when(chainRpcClient.isTransactionConfirmed("0xTxHash")).thenThrow(new RuntimeException("rpc err"));

        TransactionResult result = newChannel().execute(validRequest("r-12"));
        assertFalse(result.isSuccess());
        assertEquals("0xTxHash", result.getTxHash());
    }

    // === queryStatus ===

    @Test
    @DisplayName("queryStatus: null/空 txHash -> FAILED")
    void queryStatus_null() {
        DefaultOnChainExecutionChannel channel = newChannel();
        assertFalse(channel.queryStatus(null).isSuccess());
        assertFalse(channel.queryStatus("").isSuccess());
    }

    @Test
    @DisplayName("queryStatus: SIMULATED- 前缀 -> SUCCESS")
    void queryStatus_simulated() {
        TransactionResult result = newChannel().queryStatus("SIMULATED-abc");
        assertTrue(result.isSuccess());
        assertTrue(result.isSimulated());
    }

    @Test
    @DisplayName("queryStatus: 链确认 -> SUCCESS")
    void queryStatus_confirmed() {
        when(chainRpcClient.isTransactionConfirmed("0xTx")).thenReturn(true);
        TransactionResult result = newChannel().queryStatus("0xTx");
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("queryStatus: 链未确认 -> PENDING")
    void queryStatus_pending() {
        when(chainRpcClient.isTransactionConfirmed("0xTx")).thenReturn(false);
        TransactionResult result = newChannel().queryStatus("0xTx");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("queryStatus: 链查询抛异常 -> FAILED")
    void queryStatus_exception() {
        when(chainRpcClient.isTransactionConfirmed("0xTx")).thenThrow(new RuntimeException("rpc"));
        TransactionResult result = newChannel().queryStatus("0xTx");
        assertFalse(result.isSuccess());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}