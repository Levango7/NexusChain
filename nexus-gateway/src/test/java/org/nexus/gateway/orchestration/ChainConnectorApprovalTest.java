package org.nexus.gateway.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.model.SubmissionStatus;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
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
 * {@link ChainConnector} 的大额签名审批执行腿用例（Board 裁决 20261025-0415 §六）。
 *
 * <p>核心断言：2001 不得被记成 FAILED；approvalId 经链接表持久化；审批通过后由该笔支付
 * 自己的状态轮询推进执行；结果不可核对时留在 PROCESSING 等人工核对。</p>
 */
class ChainConnectorApprovalTest {

    private final ChainRpcClient rpc = mock(ChainRpcClient.class);
    private final SigningServiceFeignClient signing = mock(SigningServiceFeignClient.class);
    private final WalletMgmtFeignClient walletMgmt = mock(WalletMgmtFeignClient.class);
    private final SigningApprovalExecutionService approvalService =
            mock(SigningApprovalExecutionService.class);

    private ChainConnector connectorWithApproval() {
        GatewayConfig cfg = mock(GatewayConfig.class);
        GatewayConfig.ExchangeWalletConfig ew = mock(GatewayConfig.ExchangeWalletConfig.class);
        when(cfg.getExchangeWallet()).thenReturn(ew);
        when(ew.getPlatformPubkey()).thenReturn("platformPk");
        ChainConnector connector = new ChainConnector(rpc, signing, walletMgmt, cfg);
        connector.setApprovalExecutionService(approvalService);
        return connector;
    }

    private ConnectorPaymentRequest request() {
        ConnectorPaymentRequest req = new ConnectorPaymentRequest("pay_1", 50000L, "NEX", "test");
        req.setPayerAddress("0xPayer");
        req.setPayeeAddress("0xPayee");
        return req;
    }

    private static SigningOutcome awaiting() {
        return new SigningOutcome(ApprovalExecutionLink.Status.AWAITING_APPROVAL, "appr-1", null, null);
    }

    private static SigningOutcome executed(String txHash) {
        return new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, txHash, null);
    }

    @Test
    @DisplayName("createPayment: 2001 待审批→PROCESSING（不是 FAILED），且不回 txHash")
    void createPayment_awaitingApprovalStaysProcessing() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(eq("execution:CHAIN:pay_1"), eq("CHAIN"),
                    eq("platformPk"), eq("payeeHash"), any(BigDecimal.class), eq("NEX")))
                    .thenReturn(awaiting());

            ConnectorPaymentResult result = connectorWithApproval().createPayment(request());

            assertTrue(result.isSuccess(), "待审批不是失败");
            assertEquals(PaymentStatus.PROCESSING, result.getStatus());
            assertNull(result.getTransactionHash(), "未动账不得有 txHash");
            assertEquals(SubmissionStatus.SIGNING, result.getSubmissionStatus());
            // 绝不能再走一次"直接签名"，否则会创建第二条审批请求
            verify(signing, never()).signTransfer(anyString(), anyString(), any());
        }
    }

    @Test
    @DisplayName("queryPayment: 审批通过后由状态轮询执行并确认最终落 SUCCEEDED")
    void queryPayment_executesAfterApproval() {
        String connectorId;
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(awaiting());

            ChainConnector connector = connectorWithApproval();
            ConnectorPaymentResult created = connector.createPayment(request());
            connectorId = created.getConnectorPaymentId();

            when(approvalService.refresh("execution:CHAIN:pay_1")).thenReturn(awaiting());
            assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(connectorId),
                    "仍在审批中→PROCESSING");
            verify(approvalService).refresh("execution:CHAIN:pay_1");

            when(approvalService.refresh("execution:CHAIN:pay_1"))
                    .thenReturn(executed("0xRealTx"));
            when(rpc.isTransactionConfirmed("0xRealTx")).thenReturn(true);

            assertEquals(PaymentStatus.SUCCEEDED, connector.queryPayment(connectorId));
        }
    }

    @Test
    @DisplayName("queryPayment: 审批被拒/明确失败才落 FAILED")
    void queryPayment_approvalLegFailedMarksFailed() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(awaiting());

            ChainConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();

            when(approvalService.refresh("execution:CHAIN:pay_1")).thenReturn(
                    new SigningOutcome(ApprovalExecutionLink.Status.FAILED, "appr-1", null, "审批被拒"));

            assertEquals(PaymentStatus.FAILED, connector.queryPayment(connectorId));
        }
    }

    @Test
    @DisplayName("queryPayment: 结果不可核对→保持 PROCESSING 并告警，禁止重试")
    void queryPayment_unverifiedKeepsProcessing() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(awaiting());

            ChainConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();

            when(approvalService.refresh("execution:CHAIN:pay_1")).thenReturn(
                    new SigningOutcome(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN,
                            "appr-1", null, "结果未知"));

            assertEquals(PaymentStatus.PROCESSING, connector.queryPayment(connectorId));
            verify(rpc, never()).isTransactionConfirmed(anyString());
        }
    }

    @Test
    @DisplayName("createPayment: 执行结果不可核对→PROCESSING（绝不记 FAILED）")
    void createPayment_unverifiedStaysProcessing() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(
                    new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED_UNVERIFIED,
                            "appr-1", null, null));

            ConnectorPaymentResult result = connectorWithApproval().createPayment(request());

            assertTrue(result.isSuccess());
            assertEquals(PaymentStatus.PROCESSING, result.getStatus());
            assertNull(result.getTransactionHash());
        }
    }

    @Test
    @DisplayName("createPayment: 明确失败→FAILED 并带原因")
    void createPayment_explicitFailureFails() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(
                    new SigningOutcome(ApprovalExecutionLink.Status.FAILED, null, null, "余额不足"));

            ConnectorPaymentResult result = connectorWithApproval().createPayment(request());

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("余额不足"));
        }
    }

    @Test
    @DisplayName("refund: 2001 待审批→pending 结果（既非成功也非失败）+ approvalId")
    void refund_awaitingApprovalReturnsPending() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(awaiting());
            ChainConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();

            when(approvalService.signOrAwait(eq("execution:CHAIN_REFUND:" + connectorId + ":100"),
                    eq("CHAIN_REFUND"), eq("platformPk"), eq("payerHash"),
                    eq(new BigDecimal("100")), eq("NEX"))).thenReturn(awaiting());

            ConnectorRefundResult refund = connector.refund(connectorId, 100L);

            assertFalse(refund.isSuccess(), "未动账不得报退款成功");
            assertTrue(refund.isPending());
            assertEquals("appr-1", refund.getApprovalId());
            assertNull(refund.getErrorMessage());
        }
    }

    @Test
    @DisplayName("refund: 已执行→成功并把原支付置 REFUNDED")
    void refund_executedMarksRefunded() {
        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");
            when(approvalService.signOrAwait(anyString(), anyString(), anyString(), anyString(),
                    any(BigDecimal.class), anyString())).thenReturn(executed("0xPay"));
            ChainConnector connector = connectorWithApproval();
            String connectorId = connector.createPayment(request()).getConnectorPaymentId();

            ConnectorRefundResult refund = connector.refund(connectorId, 100L);

            assertTrue(refund.isSuccess());
            assertEquals(PaymentStatus.REFUNDED, connector.queryPayment(connectorId));
        }
    }

    @Test
    @DisplayName("未装配执行服务时退化为直接签名（2000 仍取真实哈希）")
    void withoutApprovalService_fallsBackToDirectSigning() {
        GatewayConfig cfg = mock(GatewayConfig.class);
        GatewayConfig.ExchangeWalletConfig ew = mock(GatewayConfig.ExchangeWalletConfig.class);
        when(cfg.getExchangeWallet()).thenReturn(ew);
        when(ew.getPlatformPubkey()).thenReturn("platformPk");
        java.util.Map<String, Object> ok = new java.util.HashMap<>();
        ok.put("statusCode", 2000);
        ok.put("data", "0xDirect");
        when(signing.signTransfer("platformPk", "payeeHash", new BigDecimal("50000"))).thenReturn(ok);

        try (MockedStatic<WalletUtils> walletUtils = Mockito.mockStatic(WalletUtils.class)) {
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayee")).thenReturn("payeeHash");
            walletUtils.when(() -> WalletUtils.addressToPubkeyHash("0xPayer")).thenReturn("payerHash");

            ConnectorPaymentResult result = new ChainConnector(rpc, signing, walletMgmt, cfg)
                    .createPayment(request());

            assertTrue(result.isSuccess());
            assertEquals("0xDirect", result.getTransactionHash());
        }
    }
}
