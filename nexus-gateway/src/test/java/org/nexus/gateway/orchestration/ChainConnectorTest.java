package org.nexus.gateway.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChainConnector}: the delegated settlement path.
 *
 * <p>Since the connector no longer builds/signs transactions itself (it delegates
 * to {@link SigningServiceFeignClient} for signing/broadcast and
 * {@link WalletMgmtFeignClient} for address resolution), these tests mock the
 * signing/wallet clients and assert the delegation contract: createPayment resolves
 * the payee, delegates signing, and returns the on-chain txHash; queryPayment polls
 * confirmation via the core RPC.</p>
 */
class ChainConnectorTest {

    private ChainConnector connectorWith(ChainRpcClient rpc,
                                          SigningServiceFeignClient signing,
                                          WalletMgmtFeignClient walletMgmt,
                                          GatewayConfig cfg) {
        return new ChainConnector(rpc, signing, walletMgmt, cfg);
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
    @DisplayName("createPayment: wallet signs -> PROCESSING with on-chain txHash")
    void createPayment_signed() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        try (MockedStatic<WalletUtils> mockedWalletUtils = Mockito.mockStatic(WalletUtils.class)) {
            mockedWalletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");

            ChainConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
            ConnectorPaymentResult r = c.createPayment(sampleRequest());

            assertTrue(r.isSuccess());
            assertEquals(PaymentStatus.PROCESSING, r.getStatus());
            assertEquals("txHash123", r.getTransactionHash());
        }
    }

    @Test
    @DisplayName("createPayment: invalid payee address -> FAILED")
    void createPayment_invalidPayee() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        try (MockedStatic<WalletUtils> mockedWalletUtils = Mockito.mockStatic(WalletUtils.class)) {
            mockedWalletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn(null);

            ChainConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
            ConnectorPaymentResult r = c.createPayment(sampleRequest());

            assertFalse(r.isSuccess());
            assertEquals(PaymentStatus.FAILED, r.getStatus());
        }
    }

    @Test
    @DisplayName("createPayment: wallet signing fails -> FAILED")
    void createPayment_signingFails() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        try (MockedStatic<WalletUtils> mockedWalletUtils = Mockito.mockStatic(WalletUtils.class)) {
            mockedWalletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn(null);

            ChainConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
            ConnectorPaymentResult r = c.createPayment(sampleRequest());

            assertFalse(r.isSuccess());
            assertEquals(PaymentStatus.FAILED, r.getStatus());
        }
    }

    @Test
    @DisplayName("createPayment: platform pubkey not configured -> FAILED")
    void createPayment_noPlatformPubkey() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);

        ChainConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith(""));
        ConnectorPaymentResult r = c.createPayment(sampleRequest());

        assertFalse(r.isSuccess());
        assertEquals(PaymentStatus.FAILED, r.getStatus());
    }

    @Test
    @DisplayName("queryPayment: confirmed on chain -> SUCCEEDED")
    void queryPayment_confirmed() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        try (MockedStatic<WalletUtils> mockedWalletUtils = Mockito.mockStatic(WalletUtils.class)) {
            mockedWalletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");
            when(rpc.isTransactionConfirmed("txHash123")).thenReturn(true);

            ChainConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
            ConnectorPaymentResult created = c.createPayment(sampleRequest());
            PaymentStatus s = c.queryPayment(created.getConnectorPaymentId());

            assertEquals(PaymentStatus.SUCCEEDED, s);
        }
    }

    @Test
    @DisplayName("queryPayment: not yet confirmed -> PROCESSING")
    void queryPayment_unconfirmed() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
        WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
        try (MockedStatic<WalletUtils> mockedWalletUtils = Mockito.mockStatic(WalletUtils.class)) {
            mockedWalletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            when(signing.signTransfer(anyString(), anyString(), Mockito.any())).thenReturn("txHash123");
            when(rpc.isTransactionConfirmed("txHash123")).thenReturn(false);

            ChainConnector c = connectorWith(rpc, signing, walletMgmt, gatewayConfigWith("platformPk"));
            ConnectorPaymentResult created = c.createPayment(sampleRequest());
            PaymentStatus s = c.queryPayment(created.getConnectorPaymentId());

            assertEquals(PaymentStatus.PROCESSING, s);
        }
    }

    @Test
    @DisplayName("healthCheck: positive height -> up")
    void healthCheck_up() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        when(rpc.getBlockHeight()).thenReturn(100L);

        ChainConnector c = connectorWith(rpc, mock(SigningServiceFeignClient.class),
                mock(WalletMgmtFeignClient.class), gatewayConfigWith("platformPk"));
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
    }

    @Test
    @DisplayName("healthCheck: RPC failure -> down")
    void healthCheck_down() {
        ChainRpcClient rpc = mock(ChainRpcClient.class);
        when(rpc.getBlockHeight()).thenThrow(new RuntimeException("rpc down"));

        ChainConnector c = connectorWith(rpc, mock(SigningServiceFeignClient.class),
                mock(WalletMgmtFeignClient.class), gatewayConfigWith("platformPk"));
        ConnectorHealth h = c.healthCheck();
        assertFalse(h.isHealthy());
    }
}
