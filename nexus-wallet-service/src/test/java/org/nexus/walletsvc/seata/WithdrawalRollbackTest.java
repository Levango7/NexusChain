package org.nexus.walletsvc.seata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.wallet.WalletUtils;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 提现事务回滚测试（Phase 4 任务 #74，设计文档 §4.6.3 / §4.5.2）。
 *
 * <p>验证 {@code DefaultWithdrawalApprovalService.executeApprovedWithdrawal} 在
 * signing-service 调用失败时的状态处理与事务行为。</p>
 *
 * <p><strong>事务边界分析</strong>（设计文档 §4.5.2）：
 * <ul>
 *   <li>{@code executeApprovedWithdrawal} 标注 {@code @GlobalTransactional} + {@code @Transactional}</li>
 *   <li>模板捕获阶段2 异常后返回（不重新抛出），此路径下事务正常提交
 *      （非终态 EXECUTED_UNVERIFIED 持久化）——2026-11-01 起不再记 FAILED，
 *      因为阶段2 的异常可能发生在广播之后，FAILED 会被当成"确定未动账"而重提</li>
 *   <li>仅当方法抛出未捕获异常时才触发回滚</li>
 * </ul>
 * </p>
 *
 * <p><strong>测试环境说明</strong>：测试 profile 中 {@code seata.enabled=false}，
 * 本测试验证的是 {@code @Transactional} 的本地事务行为（Seata AT 在 seata.enabled=false
 * 时退化为本地事务）。生产环境中 Seata Server 可用时，{@code @GlobalTransactional}
 * 会额外通过 undo_log 表实现全局回滚。</p>
 *
 * <p>验证场景：
 * <ul>
 *   <li>场景 1：signing-service 抛异常 → 状态置 EXECUTED_UNVERIFIED（结果不明，正常提交）</li>
 *   <li>场景 2：signing-service 返回 null → 状态置 EXECUTED_UNVERIFIED（结果不明）</li>
 *   <li>场景 3：signing-service 返回 2000 但无哈希 → 状态置 EXECUTED_UNVERIFIED</li>
 *   <li>场景 4：signing-service 正常返回 → 状态置 EXECUTED</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class WithdrawalRollbackTest {

    @Autowired
    private WithdrawalApprovalService withdrawalApprovalService;

    @Autowired
    private WithdrawalRequestRepository withdrawalRequestRepository;

    @Autowired
    private WhitelistEntryRepository whitelistEntryRepository;

    @MockitoBean
    private SigningServiceFeignClient signingServiceClient;

    private static final String WHITELISTED_ADDR =
            WalletUtils.pubkeyHashToAddress("eeee6666deadbeefdeadbeefdeadbeefdeadbeef");

    @BeforeEach
    void setupWhitelist() {
        if (!whitelistEntryRepository.existsByAddress(WHITELISTED_ADDR)) {
            WhitelistEntryEntity entry = new WhitelistEntryEntity();
            entry.setAddress(WHITELISTED_ADDR);
            entry.setMerchantId("merchant-rollback-test");
            entry.setAddedAt(LocalDateTime.now());
            entry.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
            entry.setActive(true);
            whitelistEntryRepository.save(entry);
        }
    }

    /** 创建一个已 APPROVED 的提现请求，供 executeApprovedWithdrawal 测试使用。 */
    private String createApprovedRequest() {
        WithdrawalRequest request = withdrawalApprovalService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");
        withdrawalApprovalService.approve(request.getRequestId(), "approver-rollback");
        return request.getRequestId();
    }

    @Test
    @DisplayName("场景 1: signing-service 抛异常 → 状态置 EXECUTED_UNVERIFIED（模板捕获后正常提交）")
    void signingServiceThrowsException_statusSetToUnverified() {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new RuntimeException("signing-service connection refused"));

        String requestId = createApprovedRequest();

        WithdrawalRequest result = withdrawalApprovalService.executeApprovedWithdrawal(requestId);

        // 模板捕获异常后不重抛；2026-11-01 fail-closed 收敛：落非终态 UNVERIFIED
        // （抛异常可能在广播之后，FAILED 可被重提 ⇒ 双重支付风险）
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus());
        assertNotNull(result.getRejectionReason());
        assertEquals(requestId, result.getRequestId());

        // 数据库验证：状态已持久化（事务正常提交，非回滚）
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, entity.getStatus());
        assertNotNull(entity.getRejectionReason());
        assertNotNull(entity.getUpdatedAt());
    }

    @Test
    @DisplayName("场景 2: signing-service 返回 null → 状态置 EXECUTED_UNVERIFIED（结果不明）")
    void signingServiceReturnsNull_statusSetToUnverified() {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(null);

        String requestId = createApprovedRequest();

        WithdrawalRequest result = withdrawalApprovalService.executeApprovedWithdrawal(requestId);

        // 2026-10-31 fail-closed 收敛：降级 null 无法证明「没广播」（熔断/读超时都可能
        // 发生在广播之后）⇒ 不得记终态 FAILED（会被重提＝二次出款），落非终态待人工核对。
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus());
        assertNotNull(result.getRejectionReason());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, entity.getStatus());
    }

    @Test
    @DisplayName("场景 3: signing-service 返回 2000 但无哈希 → 状态置 EXECUTED_UNVERIFIED")
    void signingServiceReturnsEmptyString_statusSetToUnverified() {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class))).thenReturn(okResp(""));

        String requestId = createApprovedRequest();

        WithdrawalRequest result = withdrawalApprovalService.executeApprovedWithdrawal(requestId);

        // 2000 本身报告"已签名广播"，只是没回传哈希 ⇒ 更加不能当"确定未动账"。
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, entity.getStatus());
    }

    @Test
    @DisplayName("场景 4: signing-service 正常返回 → 状态置 EXECUTED")
    void signingServiceReturnsTxHash_statusSetToExecuted() {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class))).thenReturn(okResp("0xnormalExecutionTxHash1234567890"));

        String requestId = createApprovedRequest();

        WithdrawalRequest result = withdrawalApprovalService.executeApprovedWithdrawal(requestId);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, result.getStatus());
        assertEquals("0xnormalExecutionTxHash1234567890", result.getChainTxHash());
        assertNotNull(result.getExecutedAt());

        // 数据库验证
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, entity.getStatus());
        assertEquals("0xnormalExecutionTxHash1234567890", entity.getChainTxHash());
    }

    @Test
    @DisplayName("结果不明状态同样保留 rejectionReason 供后续排查")
    void unverifiedStatusPreservesRejectionReason() {
        when(signingServiceClient.signTransfer(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new RuntimeException("detailed failure reason for debugging"));

        String requestId = createApprovedRequest();
        withdrawalApprovalService.executeApprovedWithdrawal(requestId);

        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(requestId).orElseThrow();
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, entity.getStatus());
        assertNotNull(entity.getRejectionReason());
        // 2026-08-30 断言修正：生产实现记录的是链上执行的具体错误串
        // （OnChainResult.error，经三阶段模板传递），不再有统一 'execution failed'
        // 前缀——安全属性是「具体失败原因被保留供排查」而非固定前缀。
        assertEquals(true, entity.getRejectionReason().contains("detailed failure reason for debugging"),
                "rejectionReason 应保留底层异常信息供排查: " + entity.getRejectionReason());
        assert !entity.getRejectionReason().isBlank() : "rejectionReason 不得为空白";
    }

    /** 契约修正（2026-09-10）：signTransfer 真实响应形状 {statusCode, data, message} 的成功响应构造器。 */
    private static java.util.Map<String, Object> okResp(String txHash) {
        return java.util.Map.of("statusCode", 2000, "data", txHash);
    }
}