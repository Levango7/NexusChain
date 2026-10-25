package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.client.ConsortiumRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConsortiumConnector} 的大额签名审批执行腿用例：与 chain 连接器同一套
 * "2001 不记失败 / approvalId 落链接表 / 轮询推进执行"的契约。
 */
class ConsortiumConnectorApprovalTest {

    private final ConsortiumRpcClient rpc = mock(ConsortiumRpcClient.class);
    private final SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
    private final WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
    private final SigningApprovalExecutionService approvalService =
            mock(SigningApprovalExecutionService.class);

    private ConsortiumConnector connectorWithApproval() {
        GatewayConfig cfg = mock(GatewayConfig.class);
        GatewayConfig.ExchangeWalletConfig ew = mock(GatewayConfig.ExchangeWalletConfig.class);
        when(cfg.getExchangeWallet()).thenReturn(ew);
        when(ew.getPlatformPubkey()).thenReturn("platformPk");
        ConsortiumConnector connector = new ConsortiumConnector(rpc, signing, walletMgmt, cfg);
        connector.setApprovalExecutionService(approvalService);
        return connector;
    }

    private ConnectorPaymentRequest request() {
        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_c", 80000L, "NEX", "test");
        req.setPayerAddress("0xPayer");
        req.setPayeeAddress("0xPayee");
        return req;
    }

    private static SigningOutcome awaiting() {
        return new SigningOutcome(ApprovalExecutionLink.Status.AWAITING_APPROVAL, "appr-7", null, null);
    }

    private static SigningOutcome executed(String txHash) {
        return new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, txHash, null);
    }

    private void stubAwaiting(MockedStatic<WalletUtils> walletUtils) {
        walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
        walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
        when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                any(BigDecimal.class), anyString())).thenReturn(awaiting());
    }

    @Test
    @DisplayName("createPayment: 2001 待审批→PROCESSING，不动签名客户端")
    void createPayment_awaitingApprovalStaysProcessing() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            stubAwaiting(walletUtils);

            ConnectorPaymentResult result = connectorWithApproval().createPayment(request());

            assertTrue(result.isSuccess());
            assertEquals(PaymentStatus.PROCESSING, result.getStatus());
            assertNull(result.getTransactionHash());
            verify(signing, never()).signTransfer(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("queryPayment: 审批通过后执行并按确认落 SUCCEEDED")
    void queryPayment_executesAfterApproval() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            stubAwaiting(walletUtils);
            ConsortiumConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();
            String requestKey = "execution:CONSORTIUM:pay_c";

            when(approvalService.refresh(requestKey)).thenReturn(awaiting());
            assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(connectorId));

            when(approvalService.refresh(requestKey)).thenReturn(executed("0xConsortiumTx"));
            when(rpc.isTransactionConfirmed("0xConsortiumTx")).thenReturn(true);
            assertEquals(PaymentStatus.SUCCEEDED, connector.queryPayment(connectorId));
        }
    }

    @Test
    @DisplayName("queryPayment: 审批腿明确失败→FAILED")
    void queryPayment_approvalLegFailed() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            stubAwaiting(walletUtils);
            ConsortiumConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();

            when(approvalService.refresh("execution:CONSORTIUM:pay_c")).thenReturn(
                    new SigningOutcome(ApprovalExecutionLink.Status.FAILED, "appr-7", null, "审批被拒"));

            assertEquals(PaymentStatus.FAILED, connector.queryPayment(connectorId));
        }
    }

    @Test
    @DisplayName("refund: 2001→pending（既不成功也不失败）并带 approvalId")
    void refund_awaitingApprovalReturnsPending() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            stubAwaiting(walletUtils);
            ConsortiumConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();

            when(approvalService.signOrAwait(eq("execution:CONSORTIUM_REFUND:" + connectorId + ":200"),
                    eq("CONSORTIUM_REFUND"), eq("platformPk"), eq("payerHash"),
                    eq(new BigDecimal("200")), eq("NEX"))).thenReturn(awaiting());

            ConnectorRefundResult refund = connector.refund(connectorId, 200L);

            assertFalse(refund.isSuccess());
            assertTrue(refund.isPending());
            assertEquals("appr-7", refund.getApprovalId());
        }
    }

    @Test
    @DisplayName("未装配执行服务时退化为直接签名（2000 取真实哈希）")
    void withoutApprovalService_fallsBackToDirectSigning() {
        GatewayConfig cfg = mock(GatewayConfig.class);
        GatewayConfig.ExchangeWalletConfig ew = mock(GatewayConfig.ExchangeWalletConfig.class);
        when(cfg.getExchangeWallet()).thenReturn(ew);
        when(ew.getPlatformPubkey()).thenReturn("platformPk");
        java.util.Map<String, Object> ok = new java.util.HashMap<>();
        ok.put("statusCode", 2000);
        ok.put("data", "0xDirectConsortium");
        when(signing.signTransfer("platformPk", "payeeHash", new BigDecimal("80000"))).thenReturn(ok);

        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");

            ConnectorPaymentResult result = new ConsortiumConnector(rpc, signing, walletMgmt, cfg)
                    .createPayment(request());

            assertTrue(result.isSuccess());
            assertEquals("0xDirectConsortium", result.getTransactionHash());
        }
    }
}
