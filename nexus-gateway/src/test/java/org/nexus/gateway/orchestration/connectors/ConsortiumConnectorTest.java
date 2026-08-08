package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.gateway.client.ConsortiumRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ConsortiumConnector} 单元测试：与 ChainConnector 同构，覆盖 consortium 链
 * 的支付创建、查询、退款、健康检查与 oracle 换算分支。
 */
class ConsortiumConnectorTest {

    private ConsortiumConnector connectorWith(ConsortiumRpcClient rpc,
                                                SigningServiceFeignClient signing,
                                                WalletMgmtFeignClient walletMgmt,
                                                GatewayConfig cfg) {
        return new ConsortiumConnector(rpc, signing, walletMgmt, cfg);
    }

    private GatewayConfig gatewayConfigWith(String platformPubkey) {
        GatewayConfig cfg = mock(GatewayConfig.class);
        GatewayConfig.ExchangeWalletConfig ew = mock(GatewayConfig.ExchangeWalletConfig.class);
        when(cfg.getExchangeWallet()).thenReturn(ew);
        when(ew.getPlatformPubkey()).thenReturn(platformPubkey);
        return cfg;
    }

    private ConnectorPaymentRequest sampleRequest() {
        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_1", 50000L, "NEX", "test");
        req.setPayerAddress("0xPayer");
        req.setPayeeAddress("0xPayee");
        return req;
    }

    @Test
    @DisplayName("getId/getType/isActive/feeBasisPoints/supportedCurrencies")
    void metadata() {
        ConsortiumConnector c = connectorWith(mock(ConsortiumRpcClient.class),
                mock(SigningServiceFeignClient.class), mock(WalletMgmtFeignClient.class),
                gatewayConfigWith("pk"));
        assertEquals("consortium", c.getId());
        assertEquals("consortium", c.getType());
        assertTrue(c.isActive());
        assertEquals(2, c.feeBasisPoints());
        assertTrue(c.supportedCurrencies().contains("NEX"));
    }

    @Test
    @DisplayName("createPayment: wallet signs -> PROCESSING with on-chain txHash")
    void createPayment_signed() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult r = c.createPayment(sampleRequest());

        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
        assertEquals("txHash123", r.getTransactionHash());
    }

    @Test
    @DisplayName("createPayment: invalid payee address -> FAILED")
    void createPayment_invalidPayee() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn(null);

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult r = c.createPayment(sampleRequest());

        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("createPayment: wallet signing fails -> FAILED")
    void createPayment_signingFails() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn(null);

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult r = c.createPayment(sampleRequest());

        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("createPayment: platform pubkey not configured -> FAILED")
    void createPayment_noPlatformPubkey() {
        ConsortiumConnector c = connectorWith(mock(ConsortiumRpcClient.class),
                mock(SigningServiceFeignClient.class), mock(WalletMgmtFeignClient.class),
                gatewayConfigWith(""));
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("createPayment: signing 抛异常 -> FAILED")
    void createPayment_exception() {
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenThrow(new RuntimeException("sign err"));

        ConsortiumConnector c = connectorWith(mock(ConsortiumRpcClient.class), signing, walletMgmt,
                gatewayConfigWith("platformPk"));
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("queryPayment: confirmed on consortium -> SUCCEEDED")
    void queryPayment_confirmed() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");
        when(rpc.isTransactionConfirmed("txHash123")).thenReturn(true);

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("queryPayment: not yet confirmed -> PROCESSING")
    void queryPayment_unconfirmed() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");
        when(rpc.isTransactionConfirmed("txHash123")).thenReturn(false);

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("queryPayment: 未知 connectorPaymentId -> FAILED")
    void queryPayment_unknown() {
        ConsortiumConnector c = connectorWith(mock(ConsortiumRpcClient.class),
                mock(SigningServiceFeignClient.class), mock(WalletMgmtFeignClient.class),
                gatewayConfigWith("platformPk"));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown-id"));
    }

    @Test
    @DisplayName("queryPayment: RPC 抛异常 -> PROCESSING")
    void queryPayment_rpcException() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");
        when(rpc.isTransactionConfirmed("txHash123")).thenThrow(new RuntimeException("rpc err"));

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("refund: 已知 payerHash -> 退款到 payer")
    void refund_toPayer() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        var refund = c.refund(created.getConnectorPaymentId(), 1000L);
        assertTrue(refund.isSuccess());
    }

    @Test
    @DisplayName("refund: 未知 connectorPaymentId -> FAILED")
    void refund_unknownPayment() {
        ConsortiumConnector c = connectorWith(mock(ConsortiumRpcClient.class),
                mock(SigningServiceFeignClient.class), mock(WalletMgmtFeignClient.class),
                gatewayConfigWith("platformPk"));
        var refund = c.refund("unknown", 1000L);
        assertFalse(refund.isSuccess());
    }

    @Test
    @DisplayName("refund: platform pubkey 未配置 -> FAILED")
    void refund_noPlatformPubkey() {
        ConsortiumConnector c = connectorWith(mock(ConsortiumRpcClient.class),
                mock(SigningServiceFeignClient.class), mock(WalletMgmtFeignClient.class),
                gatewayConfigWith(""));
        var refund = c.refund("any", 1000L);
        assertFalse(refund.isSuccess());
    }

    @Test
    @DisplayName("refund: signing 返回 null -> FAILED")
    void refund_signingNull() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(walletMgmt.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn(null);

        ConsortiumConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        var refund = c.refund(created.getConnectorPaymentId(), 1000L);
        assertFalse(refund.isSuccess());
    }

    @Test
    @DisplayName("healthCheck: positive height -> up")
    void healthCheck_up() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        when(rpc.getBlockHeight()).thenReturn(100L);

        ConsortiumConnector c = connectorWith(rpc, mock(SigningServiceFeignClient.class),
                mock(WalletMgmtFeignClient.class), gatewayConfigWith("platformPk"));
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
    }

    @Test
    @DisplayName("healthCheck: height <= 0 -> down")
    void healthCheck_zeroHeight() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        when(rpc.getBlockHeight()).thenReturn(0L);

        ConsortiumConnector c = connectorWith(rpc, mock(SigningServiceFeignClient.class),
                mock(WalletMgmtFeignClient.class), gatewayConfigWith("platformPk"));
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("healthCheck: RPC failure -> down")
    void healthCheck_down() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        when(rpc.getBlockHeight()).thenThrow(new RuntimeException("rpc down"));

        ConsortiumConnector c = connectorWith(rpc, mock(SigningServiceFeignClient.class),
                mock(WalletMgmtFeignClient.class), gatewayConfigWith("platformPk"));
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }

    @Test
    @DisplayName("createPayment: 非 NEX 币种 + oracle 可用 -> 换算链上金额")
    void createPayment_withOracleConversion() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        OraclePriceAdapter oracle = mock(OraclePriceAdapter.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(oracle.convertToChainAmount(BigDecimal.valueOf(50000), "NEX"))
                .thenReturn(new BigDecimal("12500"));
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash");

        GatewayConfig cfg = gatewayConfigWith("platformPk");
        ConsortiumConnector c = new ConsortiumConnector(rpc, signing, walletMgmt, cfg, oracle);

        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_1", 50000L, "USD", "test");
        req.setPayerAddress("0xPayer");
        req.setPayeeAddress("0xPayee");
        ConnectorPaymentResult r = c.createPayment(req);

        assertTrue(r.isSuccess());
        // 验证 signTransfer 收到换算后的金额（12500）
        org.mockito.Mockito.verify(signing).signTransfer(anyString(), anyString(), eq(new BigDecimal("12500")));
    }

    @Test
    @DisplayName("createPayment: 非 NEX 币种 + oracle 返回 null -> 回退原始金额")
    void createPayment_oracleNull_fallback() {
        ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        OraclePriceAdapter oracle = mock(OraclePriceAdapter.class);
        when(walletMgmt.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        when(oracle.convertToChainAmount(BigDecimal.valueOf(50000), "NEX")).thenReturn(null);
        when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash");

        GatewayConfig cfg = gatewayConfigWith("platformPk");
        ConsortiumConnector c = new ConsortiumConnector(rpc, signing, walletMgmt, cfg, oracle);

        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_1", 50000L, "USD", "test");
        req.setPayerAddress("0xPayer");
        req.setPayeeAddress("0xPayee");
        ConnectorPaymentResult r = c.createPayment(req);

        assertTrue(r.isSuccess());
        org.mockito.Mockito.verify(signing).signTransfer(anyString(), anyString(), eq(BigDecimal.valueOf(50000)));
    }
}