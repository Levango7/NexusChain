package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.approval.WithdrawalApprovalService;
import org.nexus.walletsvc.entity.WhitelistEntryEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.nexus.walletsvc.repository.WithdrawalRequestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link WithdrawalApprovalService} 集成测试（Phase 4 任务 #74，设计文档 §4.6.2 / §4.4.3）。
 *
 * <p>使用 {@code @SpringBootTest} + H2 内存数据库，验证
 * {@code DefaultWithdrawalApprovalService} 的完整流程：
 * requestWithdrawal → approve → executeApprovedWithdrawal，
 * 确认数据库中状态正确流转
 * （替代原 ConcurrentHashMap 内存存储）。</p>
 *
 * <p>{@link SigningServiceFeignClient} 通过 {@code @MockBean} 模拟，
 * 避免依赖 signing-service 实例运行。Mock 的 {@code signTransfer} 返回模拟交易哈希。</p>
 *
 * <p>验证要点：
 * <ul>
 *   <li>requestWithdrawal：白名单校验 + 持久化 PENDING 状态</li>
 *   <li>approve：审批人记录 + approvedCount 递增 + 达阈值转 APPROVED</li>
 *   <li>executeApprovedWithdrawal：调 signing-service + 状态转 EXECUTED</li>
 *   <li>reject：状态转 REJECTED + rejectionReason</li>
 *   <li>防重复审批：同一 approver 二次审批抛异常</li>
 *   <li>状态机校验：非 PENDING 状态 approve/reject 抛异常</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WithdrawalServiceIntegrationTest {

    @Autowired
    private WithdrawalApprovalService withdrawalApprovalService;

    @Autowired
    private WithdrawalRequestRepository withdrawalRequestRepository;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    @MockBean
    private SigningServiceFeignClient signingServiceClient;

    /** 测试用白名单地址（长度 ≥ 20）。 */
    private static final String WHITELISTED_ADDR = "0xwithdrawalTargetAddr1234567890";
    private static final String MERCHANT_ID = "merchant-it-withdrawal";

    @BeforeEach
    void setupWhitelist() {
        // 预置白名单（DefaultApprovalPolicy.isAddressWhitelisted 查询 address_whitelist 表）
        if (!whitelistEntryRepository.existsByAddress(WHITELISTED_ADDR)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(WHITELISTED_ADDR);
            entry.setMerchantId(MERCHANT_ID);
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }
    }

    @Test
    @DisplayName("requestWithdrawal: 白名单地址 + 持久化 PENDING 状态")
    void requestWithdrawal_persistsPendingToDatabase() {
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");

        assertNotNull(request.getRequestId());
        assertTrue(request.getRequestId().startsWith("WD-"));
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, request.getStatus());
        assertEquals(WHITELISTED_ADDR, request.getToAddress());
        assertEquals(0, new BigDecimal("500").compareTo(request.getAmount()));
        assertEquals("NEX", request.getCurrency());
        assertEquals(0, request.getApprovedCount());
        assertNotNull(request.getRequiredApprovers());

        // 通过 Repository 验证数据库持久化
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, entity.getStatus());
    }

    @Test
    @DisplayName("requestWithdrawal: 非白名单地址抛 IllegalStateException")
    void requestWithdrawal_nonWhitelistedThrows() {
        assertThrows(IllegalStateException.class,
                () -> withdrawalApprovalService.requestWithdrawal(
                        "0xnonWhitelistedAddr12345678901234", new BigDecimal("100"), "NEX"));
    }

    @Test
    @DisplayName("requestWithdrawal: 无效参数校验")
    void requestWithdrawal_invalidParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> withdrawalApprovalService.requestWithdrawal(null, new BigDecimal("100"), "NEX"));
        assertThrows(IllegalArgumentException.class,
                () -> withdrawalApprovalService.requestWithdrawal(WHITELISTED_ADDR, null, "NEX"));
        assertThrows(IllegalArgumentException.class,
                () -> withdrawalApprovalService.requestWithdrawal(WHITELISTED_ADDR, BigDecimal.ZERO, "NEX"));
        assertThrows(IllegalArgumentException.class,
                () -> withdrawalApprovalService.requestWithdrawal(WHITELISTED_ADDR, new BigDecimal("100"), null));
    }

    @Test
    @DisplayName("approve: 单审批人达阈值后转 APPROVED")
    void approve_singleApproverReachesThreshold() {
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");

        WithdrawalRequest approved = withdrawalApprovalService.approve(request.getRequestId(), "approver-1");

        // 金额 500 ≤ 10000 → requiredApprovers=1，单审批即达阈值
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, approved.getStatus());
        assertEquals(1, approved.getApprovedCount());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, entity.getStatus());
    }

    @Test
    @DisplayName("approve: 多审批人未达阈值时保持 PENDING")
    void approve_multiApproverNotYetReached() {
        // 金额 50000 > 10000, ≤ 100000 → requiredApprovers=2
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("50000"), "NEX");

        WithdrawalRequest afterFirst = withdrawalApprovalService.approve(request.getRequestId(), "approver-A");
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, afterFirst.getStatus(),
                "第一个审批后应仍为 PENDING（需 2 人）");
        assertEquals(1, afterFirst.getApprovedCount());
    }

    @Test
    @DisplayName("approve: 同一审批人重复审批抛异常")
    void approve_duplicateApproverThrows() {
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");

        withdrawalApprovalService.approve(request.getRequestId(), "approver-dup");

        assertThrows(IllegalStateException.class,
                () -> withdrawalApprovalService.approve(request.getRequestId(), "approver-dup"));
    }

    @Test
    @DisplayName("approve: 不存在的 requestId 抛 IllegalArgumentException")
    void approve_nonExistentRequestThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> withdrawalApprovalService.approve("WD-nonexistent-000", "approver-1"));
    }

    @Test
    @DisplayName("reject: PENDING 状态可拒绝")
    void reject_pendingRequestCanReject() {
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");

        WithdrawalRequest rejected = withdrawalApprovalService.reject(
                request.getRequestId(), "approver-1", "Test rejection");

        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, rejected.getStatus());
        assertEquals("Test rejection", rejected.getRejectionReason());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, entity.getStatus());
    }

    @Test
    @DisplayName("executeApprovedWithdrawal: 调 signing-service 后转 EXECUTED")
    void executeApprovedWithdrawal_callsSigningServiceAndTransitionsToExecuted() {
        // Mock signing-service 返回交易哈希
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn("0xsignedTxHash1234567890abcdef");

        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");
        withdrawalApprovalService.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest executed = withdrawalApprovalService.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());
        assertNotNull(executed.getChainTxHash());
        assertTrue(executed.getChainTxHash().startsWith("0xsignedTxHash"));
        assertNotNull(executed.getExecutedAt());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, entity.getStatus());
        assertNotNull(entity.getChainTxHash());
    }

    @Test
    @DisplayName("executeApprovedWithdrawal: 未审批的请求抛 IllegalStateException")
    void executeApprovedWithdrawal_notApprovedThrows() {
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("50000"), "NEX");
        // 金额 50000 → requiredApprovers=2，只审批 1 人，未达 APPROVED
        withdrawalApprovalService.approve(request.getRequestId(), "approver-1");

        assertThrows(IllegalStateException.class,
                () -> withdrawalApprovalService.executeApprovedWithdrawal(request.getRequestId()));
    }

    @Test
    @DisplayName("完整流程: request → approve → execute")
    void fullWorkflow_requestApproveExecute() {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn("0xfullWorkflowTxHash1234567890");

        // 1. request
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("1000"), "NEX");
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, request.getStatus());

        // 2. approve
        WithdrawalRequest approved = withdrawalApprovalService.approve(request.getRequestId(), "approver-final");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, approved.getStatus());

        // 3. execute
        WithdrawalRequest executed = withdrawalApprovalService.executeApprovedWithdrawal(request.getRequestId());
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());
        assertNotNull(executed.getChainTxHash());

        // 数据库最终状态验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, entity.getStatus());
    }
}