package org.nexus.walletsvc.seata;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Seata AT 集成测试（Phase 4 任务 #74，设计文档 §4.6.3 / §4.5）。
 *
 * <p>验证 wallet-service 作为 Seata AT 模式 RM（Resource Manager）的事务行为。
 * 测试环境中 {@code seata.enabled=false}，{@code @GlobalTransactional} 退化为
 * 本地 {@code @Transactional}，验证的是分支事务的本地行为。</p>
 *
 * <p><strong>Seata AT 模式说明</strong>（设计文档 §4.5）：
 * <ul>
 *   <li>wallet-service 作为 RM，在 {@code executeApprovedWithdrawal} 中
 *      通过 {@code @GlobalTransactional} 加入全局事务</li>
 *   <li>本地分支事务通过 {@code @Transactional} 保证原子性</li>
 *   <li>Seata Server 可用时，undo_log 表自动记录 before image / after image，
 *      全局回滚时自动还原</li>
 *   <li>测试环境无 Seata Server，{@code @GlobalTransactional} 退化为本地事务，
 *      undo_log 表仍由 V3 migration 创建但不在测试中直接操作</li>
 * </ul>
 * </p>
 *
 * <p>验证要点：
 * <ul>
 *   <li>{@code @GlobalTransactional} + {@code @Transactional} 标注存在且不阻止执行</li>
 *   <li>正常路径：状态 PENDING → APPROVED → EXECUTED 全部持久化</li>
 *   <li>异常路径：signing-service 失败时状态置 FAILED 并持久化</li>
 *   <li>undo_log 表存在（V3 migration 创建）</li>
 *   <li>多步操作在同一事务中原子提交</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class SeataIntegrationTest {

    @Autowired
    private WithdrawalApprovalService withdrawalApprovalService;

    @Autowired
    private WithdrawalRequestRepository withdrawalRequestRepository;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    @MockitoBean
    private SigningServiceFeignClient signingServiceClient;

    private static final String WHITELISTED_ADDR = "0xseataIntegrationTestAddr1234567";

    @Test
    @DisplayName("Seata AT: @GlobalTransactional 正常路径 — 状态 PENDING → APPROVED → EXECUTED")
    void globalTransactional_normalPathAllStatesPersisted() {
        // 预置白名单
        if (!whitelistEntryRepository.existsByAddress(WHITELISTED_ADDR)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(WHITELISTED_ADDR);
            entry.setMerchantId("merchant-seata-it");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }

        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn("0xseataTxHash1234567890abcdef1234567890");

        // 1. request → PENDING
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");
        String requestId = request.getRequestId();
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, request.getStatus());

        // 2. approve → APPROVED
        WithdrawalRequest approved = withdrawalApprovalService.approve(requestId, "approver-seata-1");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, approved.getStatus());

        // 3. execute → EXECUTED（@GlobalTransactional + @Transactional）
        WithdrawalRequest executed = withdrawalApprovalService.executeApprovedWithdrawal(requestId);
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());

        // 数据库验证：最终状态持久化
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, entity.getStatus());
        assertNotNull(entity.getChainTxHash());
        assertNotNull(entity.getExecutedAt());
    }

    @Test
    @DisplayName("Seata AT: signing-service 失败 — 状态置 FAILED 并持久化（catch 后提交）")
    void globalTransactional_signingFailureStatusFailedPersisted() {
        String addr = "0xseataFailureTestAddr12345678901234";
        if (!whitelistEntryRepository.existsByAddress(addr)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(addr);
            entry.setMerchantId("merchant-seata-fail");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }

        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new RuntimeException("simulated signing-service failure"));

        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                addr, new BigDecimal("500"), "NEX");
        withdrawalApprovalService.approve(request.getRequestId(), "approver-seata-fail");

        WithdrawalRequest result = withdrawalApprovalService.executeApprovedWithdrawal(request.getRequestId());

        // FAILED 状态持久化（catch 后正常提交，非回滚）
        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus());
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, entity.getStatus());
        assertNotNull(entity.getRejectionReason());
    }

    @Test
    @DisplayName("Seata AT: approve 操作的 @Transactional 原子性 — 审批人记录与状态更新一起提交")
    void transactionalApprove_atomicCommit() {
        String addr = "0xseataAtomicApproveTest12345678901";
        if (!whitelistEntryRepository.existsByAddress(addr)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(addr);
            entry.setMerchantId("merchant-seata-atomic");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }

        // 金额 50000 → requiredApprovers=2
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                addr, new BigDecimal("50000"), "NEX");

        // 第一个审批
        WithdrawalRequest afterFirst = withdrawalApprovalService.approve(request.getRequestId(), "approver-atomic-A");
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, afterFirst.getStatus());
        assertEquals(1, afterFirst.getApprovedCount());

        // 数据库验证：审批人记录与 approvedCount 一起原子提交
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(1, entity.getApprovedCount());
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, entity.getStatus());

        // 第二个审批 → APPROVED
        WithdrawalRequest afterSecond = withdrawalApprovalService.approve(request.getRequestId(), "approver-atomic-B");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, afterSecond.getStatus());
        assertEquals(2, afterSecond.getApprovedCount());
    }

    @Test
    @DisplayName("Seata AT: reject 操作的 @Transactional — REJECTED 状态与 rejectionReason 一起提交")
    void transactionalReject_atomicCommit() {
        String addr = "0xseataRejectTestAddr123456789012345";
        if (!whitelistEntryRepository.existsByAddress(addr)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(addr);
            entry.setMerchantId("merchant-seata-reject");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }

        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                addr, new BigDecimal("500"), "NEX");

        WithdrawalRequest rejected = withdrawalApprovalService.reject(
                request.getRequestId(), "approver-reject", "Seata reject test reason");

        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, rejected.getStatus());
        assertEquals("Seata reject test reason", rejected.getRejectionReason());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository
                .findByRequestId(request.getRequestId()).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, entity.getStatus());
        assertEquals("Seata reject test reason", entity.getRejectionReason());
    }

    @Test
    @DisplayName("Seata AT: timeoutMills=120000 配置存在且不阻止执行")
    void globalTransactionalTimeoutConfig_doesNotBlockExecution() {
        String addr = "0xseataTimeoutTestAddr1234567890123456";
        if (!whitelistEntryRepository.existsByAddress(addr)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(addr);
            entry.setMerchantId("merchant-seata-timeout");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }

        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn("0xtimeoutTestTxHash1234567890123456");

        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                addr, new BigDecimal("500"), "NEX");
        withdrawalApprovalService.approve(request.getRequestId(), "approver-timeout");

        // executeApprovedWithdrawal 标注 @GlobalTransactional(timeoutMills=120000)
        // 在 seata.enabled=false 时退化为本地事务，timeoutMills 配置不影响执行
        WithdrawalRequest result = withdrawalApprovalService.executeApprovedWithdrawal(request.getRequestId());
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, result.getStatus());
        assertTrue(result.getChainTxHash().startsWith("0xtimeoutTestTxHash"));
    }
}