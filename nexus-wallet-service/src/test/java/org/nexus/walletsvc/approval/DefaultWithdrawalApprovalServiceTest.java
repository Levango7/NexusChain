package org.nexus.walletsvc.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.signing.ApprovalPolicy;
import org.nexus.sdk.wallet.WithdrawalRequest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultWithdrawalApprovalService} 单元测试（Phase 3 任务 T13）。
 *
 * <p>验证多审批人提现工作流（request → approve → reject → execute），
 * 通过 {@link MockitoExtension} Mock {@link SigningServiceFeignClient}，
 * 覆盖正常流程与异常流程（Feign 调用失败 / 返回 null / 状态机非法迁移）。</p>
 *
 * <p>设计文档 §4.4.3：{@code executeApprovedWithdrawal} 通过 Feign 调
 * signing-service 的 {@code /api/v1/transfers/sign} 完成签名广播；
 * FallbackFactory 返回 null 时本服务置 FAILED。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultWithdrawalApprovalServiceTest {

    /** 测试用白名单地址（长度 ≥ 20，满足 DefaultAddressWhitelistService 校验，但本服务不校验地址格式）。 */
    private static final String WHITELISTED_ADDR = "0xwhitelisted1234567890";
    private static final String PLATFORM_WALLET = "PLATFORM_HOT_WALLET";

    @Mock private SigningServiceFeignClient signingServiceClient;

    private DefaultApprovalPolicy approvalPolicy;
    private DefaultWithdrawalApprovalService service;

    @BeforeEach
    void setUp() {
        approvalPolicy = new DefaultApprovalPolicy();
        approvalPolicy.addToWhitelist(WHITELISTED_ADDR);
        service = new DefaultWithdrawalApprovalService(
                approvalPolicy, signingServiceClient, PLATFORM_WALLET);
    }

    // ==================== requestWithdrawal ====================

    @Test
    void requestWithdrawal_smallAmountNeedsOneApprover() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("5000"), "NEX");

        assertNotNull(request.getRequestId());
        assertTrue(request.getRequestId().startsWith("WD-"));
        assertEquals(WHITELISTED_ADDR, request.getToAddress());
        assertEquals(0, new BigDecimal("5000").compareTo(request.getAmount()));
        assertEquals("NEX", request.getCurrency());
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, request.getStatus());
        assertEquals(Integer.valueOf(1), request.getRequiredApprovers());
        assertEquals(Integer.valueOf(0), request.getApprovedCount());
        assertNotNull(request.getCreatedAt());
        // 请求阶段不应触发签名服务
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    void requestWithdrawal_mediumAmountNeedsTwoApprovers() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("50000"), "NEX");
        assertEquals(Integer.valueOf(2), request.getRequiredApprovers());
    }

    @Test
    void requestWithdrawal_largeAmountNeedsThreeApprovers() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500000"), "NEX");
        assertEquals(Integer.valueOf(3), request.getRequiredApprovers());
    }

    @Test
    void requestWithdrawal_nullToThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.requestWithdrawal(null, new BigDecimal("100"), "NEX"));
        assertTrue(ex.getMessage().contains("to address is required"));
    }

    @Test
    void requestWithdrawal_emptyToThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestWithdrawal("", new BigDecimal("100"), "NEX"));
    }

    @Test
    void requestWithdrawal_nullAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestWithdrawal(WHITELISTED_ADDR, null, "NEX"));
    }

    @Test
    void requestWithdrawal_zeroAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestWithdrawal(WHITELISTED_ADDR, BigDecimal.ZERO, "NEX"));
    }

    @Test
    void requestWithdrawal_negativeAmountThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestWithdrawal(WHITELISTED_ADDR, new BigDecimal("-1"), "NEX"));
    }

    @Test
    void requestWithdrawal_nullCurrencyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.requestWithdrawal(WHITELISTED_ADDR, new BigDecimal("100"), null));
    }

    @Test
    void requestWithdrawal_nonWhitelistedThrows() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.requestWithdrawal("0xunknown1234567890", new BigDecimal("100"), "NEX"));
        assertTrue(ex.getMessage().contains("not whitelisted"));
    }

    // ==================== approve ====================

    @Test
    void approve_reachesThresholdTransitionsToApproved() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("50000"), "NEX"); // 需 2 人
        assertEquals(Integer.valueOf(2), request.getRequiredApprovers());

        // 第 1 个审批人：仍未达阈值，保持 PENDING
        WithdrawalRequest afterFirst = service.approve(request.getRequestId(), "approver-1");
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, afterFirst.getStatus());
        assertEquals(Integer.valueOf(1), afterFirst.getApprovedCount());
        assertTrue(afterFirst.getApprovers().contains("approver-1"));

        // 第 2 个审批人：达到阈值，转 APPROVED
        WithdrawalRequest approved = service.approve(request.getRequestId(), "approver-2");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, approved.getStatus());
        assertEquals(Integer.valueOf(2), approved.getApprovedCount());
    }

    @Test
    void approve_singleApproverImmediatelyApproved() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX"); // 需 1 人

        WithdrawalRequest approved = service.approve(request.getRequestId(), "approver-1");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, approved.getStatus());
        assertEquals(Integer.valueOf(1), approved.getApprovedCount());
    }

    @Test
    void approve_nullApprovalIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.approve(null, "approver-1"));
    }

    @Test
    void approve_nullApproverIdThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        assertThrows(IllegalArgumentException.class,
                () -> service.approve(request.getRequestId(), null));
    }

    @Test
    void approve_unknownRequestThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.approve("WD-nonexistent", "approver-1"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void approve_duplicateApproverThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("50000"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.approve(request.getRequestId(), "approver-1"));
        assertTrue(ex.getMessage().contains("already approved"));
    }

    @Test
    void approve_nonPendingThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1"); // 转 APPROVED

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.approve(request.getRequestId(), "approver-2"));
        assertTrue(ex.getMessage().contains("not pending"));
    }

    // ==================== reject ====================

    @Test
    void reject_setsRejectedWithReason() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");

        WithdrawalRequest rejected = service.reject(
                request.getRequestId(), "approver-1", "suspicious activity");

        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, rejected.getStatus());
        assertEquals("suspicious activity", rejected.getRejectionReason());
    }

    @Test
    void reject_nullReasonUsesDefault() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");

        WithdrawalRequest rejected = service.reject(
                request.getRequestId(), "approver-1", null);

        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, rejected.getStatus());
        assertNotNull(rejected.getRejectionReason());
        assertTrue(rejected.getRejectionReason().contains("approver-1"));
    }

    @Test
    void reject_nullApprovalIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reject(null, "approver-1", "reason"));
    }

    @Test
    void reject_nullApproverIdThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        assertThrows(IllegalArgumentException.class,
                () -> service.reject(request.getRequestId(), null, "reason"));
    }

    @Test
    void reject_unknownRequestThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.reject("WD-nonexistent", "approver-1", "reason"));
    }

    @Test
    void reject_nonPendingThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1"); // 转 APPROVED

        assertThrows(IllegalStateException.class,
                () -> service.reject(request.getRequestId(), "approver-2", "reason"));
    }

    // ==================== executeApprovedWithdrawal ====================

    @Test
    void execute_feignReturnsTxHash_setsExecuted() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        String signedTxHash = "0xsignedtxhash1234567890abcdef";
        when(signingServiceClient.signTransfer(
                eq(PLATFORM_WALLET), eq(WHITELISTED_ADDR), eq(new BigDecimal("100"))))
                .thenReturn(signedTxHash);

        WithdrawalRequest executed = service.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());
        assertEquals(signedTxHash, executed.getChainTxHash());
        assertNotNull(executed.getExecutedAt());
        verify(signingServiceClient).signTransfer(
                PLATFORM_WALLET, WHITELISTED_ADDR, new BigDecimal("100"));
    }

    @Test
    void execute_feignReturnsNull_setsFailed() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        when(signingServiceClient.signTransfer(any(), any(), any())).thenReturn(null);

        WithdrawalRequest executed = service.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, executed.getStatus());
        assertNotNull(executed.getRejectionReason());
        assertTrue(executed.getRejectionReason().contains("empty result"));
        assertNull(executed.getChainTxHash());
    }

    @Test
    void execute_feignReturnsEmptyString_setsFailed() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        when(signingServiceClient.signTransfer(any(), any(), any())).thenReturn("");

        WithdrawalRequest executed = service.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, executed.getStatus());
        assertNotNull(executed.getRejectionReason());
    }

    @Test
    void execute_feignThrowsException_setsFailed() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        when(signingServiceClient.signTransfer(any(), any(), any()))
                .thenThrow(new RuntimeException("signing-service unavailable"));

        WithdrawalRequest executed = service.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, executed.getStatus());
        assertNotNull(executed.getRejectionReason());
        assertTrue(executed.getRejectionReason().contains("execution failed"));
        assertTrue(executed.getRejectionReason().contains("signing-service unavailable"));
    }

    @Test
    void execute_feignThrowsFeignLikeException_setsFailed() {
        // 模拟 Feign 降级场景：签名服务返回 5xx，Feign 抛异常透传到本服务
        // （用 RuntimeException 模拟 FeignException 以避免跨 feign 版本构造签名差异）
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        when(signingServiceClient.signTransfer(any(), any(), any()))
                .thenThrow(new RuntimeException("feign.FeignException: signing-service 500"));

        WithdrawalRequest executed = service.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, executed.getStatus());
        assertNotNull(executed.getRejectionReason());
        assertTrue(executed.getRejectionReason().contains("signing-service 500"));
    }

    @Test
    void execute_nullApprovalIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.executeApprovedWithdrawal(null));
    }

    @Test
    void execute_unknownRequestThrows() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.executeApprovedWithdrawal("WD-nonexistent"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void execute_pendingRequestThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        // 未审批，状态为 PENDING

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.executeApprovedWithdrawal(request.getRequestId()));
        assertTrue(ex.getMessage().contains("not approved"));
        // 未触发签名服务
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    void execute_rejectedRequestThrows() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        service.reject(request.getRequestId(), "approver-1", "suspicious");

        assertThrows(IllegalStateException.class,
                () -> service.executeApprovedWithdrawal(request.getRequestId()));
    }

    @Test
    void execute_noSigningClient_usesSimulatedTxHash() {
        // 验证 fallback 路径：signingServiceClient=null 时使用 SIMULATED txHash
        DefaultWithdrawalApprovalService standaloneService =
                new DefaultWithdrawalApprovalService(approvalPolicy);
        WithdrawalRequest request = standaloneService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        standaloneService.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest executed = standaloneService.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());
        assertNotNull(executed.getChainTxHash());
        assertTrue(executed.getChainTxHash().startsWith("SIMULATED-"));
        assertNotNull(executed.getExecutedAt());
    }

    @Test
    void execute_emptyPlatformWallet_usesDefault() {
        // 验证 @Value 注入空字符串时回退到默认平台钱包地址
        DefaultWithdrawalApprovalService svc = new DefaultWithdrawalApprovalService(
                approvalPolicy, signingServiceClient, "");
        WithdrawalRequest request = svc.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");
        svc.approve(request.getRequestId(), "approver-1");

        when(signingServiceClient.signTransfer(
                eq("PLATFORM_HOT_WALLET"), eq(WHITELISTED_ADDR), any()))
                .thenReturn("0xtx");

        WithdrawalRequest executed = svc.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());
        verify(signingServiceClient).signTransfer(
                eq("PLATFORM_HOT_WALLET"), eq(WHITELISTED_ADDR), any());
    }

    // ==================== getRequest ====================

    @Test
    void getRequest_returnsExistingRequest() {
        WithdrawalRequest request = service.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("100"), "NEX");

        WithdrawalRequest fetched = service.getRequest(request.getRequestId());
        assertNotNull(fetched);
        assertEquals(request.getRequestId(), fetched.getRequestId());
    }

    @Test
    void getRequest_unknownReturnsNull() {
        assertNull(service.getRequest("WD-nonexistent"));
    }

    @Test
    void getRequest_nullReturnsNull() {
        assertNull(service.getRequest(null));
    }
}
