package org.nexus.walletsvc.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.client.feign.SigningResponses;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalApproverEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.execution.ExecutionRequest;
import org.nexus.walletsvc.execution.OnChainResult;
import org.nexus.walletsvc.execution.ThreePhaseExecutionTemplate;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;
import org.nexus.walletsvc.repository.WithdrawalApproverRepository;
import org.nexus.walletsvc.repository.WithdrawalRequestRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 大额提现「签名侧转人工审批」（{@code statusCode=2001}）及其推进路径的回归测试。
 *
 * <p><b>为什么需要这个测试类</b>：修复前 {@code executeApprovedWithdrawal} 用
 * {@link SigningResponses#txHash} 提取哈希，而 2001 响应<b>没有 {@code data} 字段</b>，
 * 取哈希必然为 null ⇒ 判为「签名失败」⇒ 提现置 {@code FAILED}。后果是双重的：
 * 钱没出去但单据死在终态（CAS 只认 {@code APPROVED → EXECUTING}，无法重入），
 * 同时签名侧那条审批请求永远没人消费（悬空审批）。</p>
 *
 * <p>既有用例全部 stub 2000 成功 / null / 空串 / 抛异常，<b>没有一个构造过 2001</b>，
 * 因此缺陷能长期存活。本类专门锁住这条分支及其推进语义。</p>
 *
 * <h3>三种终局必须分清（这是本类的核心区分）</h3>
 * <ul>
 *   <li>{@code EXECUTED}：确定成功，有 txHash；</li>
 *   <li>{@code APPROVED}：钱<b>没</b>出去，单子仍存活，等推进腿再试（2001 / 5000 / 5001 / 状态未知）；</li>
 *   <li>{@code EXECUTED_UNVERIFIED}：钱<b>可能</b>已经出去，禁止重试，交人工核对；</li>
 *   <li>{@code FAILED}：确定没出去，终态。</li>
 * </ul>
 * <p>把 {@code EXECUTED_UNVERIFIED} 误记成 {@code FAILED}，运维会按"没出账"处置并重复发起 ⇒ 二次放款。</p>
 *
 * <p><b>两条执行路径都覆盖</b>：5 参构造器（{@code threePhaseTemplate == null}）走
 * {@code executeInline}，6 参走 {@code executeWithThreePhaseTemplate}。两条路径的三态分派
 * 是各自独立实现的，修一条漏一条极常见，故两条都要有用例。</p>
 *
 * <p><b>断言位置说明</b>：{@code WithdrawalRequestMapper.toDto} 不拷贝
 * {@code signingApprovalId}（SDK DTO 无该字段），故 approvalId 的断言一律落在实体
 * （{@code requestStore}）上，而非返回的 DTO。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalSigningApprovalTest {

    /** 测试用白名单地址（长度 ≥ 20）。 */
    private static final String WHITELISTED_ADDR = "0xwhitelisted1234567890";
    private static final String PLATFORM_WALLET = "PLATFORM_HOT_WALLET";
    private static final String APPROVAL_ID = "SAP-20261027-0001";
    private static final String TX_HASH = "0xsignedtxhash1234567890abcdef";

    /**
     * 恰好等于 wallet 的 {@code SMALL_THRESHOLD}（10000），也是 signing 侧
     * {@code large-amount-threshold} 的默认值。
     *
     * <p>选这个金额是为了让用例<b>真的跨过签名侧阈值</b>（{@code amount >= 10000} 即转人工审批），
     * 同时 wallet 侧仍只需 1 个审批人（{@code amount <= 10000}），一个 approver 就能把单据
     * 推到 APPROVED 再触发执行 —— 这是复现该缺陷最便宜的路径。</p>
     */
    private static final BigDecimal THRESHOLD_AMOUNT = new BigDecimal("10000");

    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WhitelistEntryRepository whitelistEntryRepository;
    @Mock private WithdrawalRequestRepository withdrawalRequestRepository;
    @Mock private WithdrawalApproverRepository withdrawalApproverRepository;
    @Mock private ThreePhaseExecutionTemplate threePhaseTemplate;

    /** 模拟 withdrawal_requests 表（requestId → entity）。 */
    private final Map<String, WithdrawalRequestEntity> requestStore = new ConcurrentHashMap<>();
    /** 模拟 withdrawal_approvers 表（requestId → approvers）。 */
    private final Map<String, List<WithdrawalApproverEntity>> approverStore = new ConcurrentHashMap<>();

    private DefaultApprovalPolicy approvalPolicy;
    /** 5 参构造器：threePhaseTemplate == null → executeInline。 */
    private DefaultWithdrawalApprovalService inlineService;
    /** 6 参构造器：threePhaseTemplate != null → executeWithThreePhaseTemplate。 */
    private DefaultWithdrawalApprovalService templateService;

    @BeforeEach
    void setUp() {
        requestStore.clear();
        approverStore.clear();

        when(whitelistEntryRepository.existsByAddressAndActiveTrue(anyString()))
                .thenAnswer(inv -> WHITELISTED_ADDR.equals(inv.getArgument(0)));

        when(withdrawalRequestRepository.findByRequestId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(requestStore.get(inv.getArgument(0))));

        when(withdrawalRequestRepository.save(any(WithdrawalRequestEntity.class))).thenAnswer(inv -> {
            WithdrawalRequestEntity entity = inv.getArgument(0);
            // 模拟 @PrePersist：单测无 JPA 容器，手动补时间戳保持 Entity 生命周期语义
            if (entity.getCreatedAt() == null) {
                entity.setCreatedAt(LocalDateTime.now());
            }
            if (entity.getUpdatedAt() == null) {
                entity.setUpdatedAt(LocalDateTime.now());
            }
            requestStore.put(entity.getRequestId(), entity);
            return entity;
        });

        // 模拟「条件 UPDATE」语义：仅当当前状态等于 from 时改为 to 并返回 1，否则 0。
        // 与数据库行级锁下的 `UPDATE ... WHERE status = :from` 一致。
        when(withdrawalRequestRepository.compareAndSetStatus(anyString(), any(), any()))
                .thenAnswer(inv -> {
                    WithdrawalRequestEntity entity = requestStore.get(inv.getArgument(0));
                    WithdrawalRequest.WithdrawalStatus from = inv.getArgument(1);
                    WithdrawalRequest.WithdrawalStatus to = inv.getArgument(2);
                    if (entity != null && entity.getStatus() == from) {
                        entity.setStatus(to);
                        return 1;
                    }
                    return 0;
                });

        when(withdrawalApproverRepository.findByRequestId(anyString()))
                .thenAnswer(inv -> new ArrayList<>(
                        approverStore.getOrDefault(inv.getArgument(0), Collections.emptyList())));
        when(withdrawalApproverRepository.existsByRequestIdAndApproverId(anyString(), anyString()))
                .thenAnswer(inv -> approverStore
                        .getOrDefault(inv.getArgument(0), Collections.emptyList()).stream()
                        .anyMatch(a -> a.getApproverId().equals(inv.getArgument(1))));
        when(withdrawalApproverRepository.countByRequestId(anyString()))
                .thenAnswer(inv -> (long) approverStore
                        .getOrDefault(inv.getArgument(0), Collections.emptyList()).size());
        when(withdrawalApproverRepository.save(any(WithdrawalApproverEntity.class))).thenAnswer(inv -> {
            WithdrawalApproverEntity approver = inv.getArgument(0);
            approverStore.computeIfAbsent(approver.getRequestId(), k -> new ArrayList<>()).add(approver);
            return approver;
        });

        // 模板路径：mock 模板但按契约依次驱动 phase1/phase2/phase3 回调，
        // 使 phase2/phase3 的真实逻辑被执行（而不是被 mock 掉）。
        // 手法照抄 nexus-gateway PaymentServiceImplRefundApprovalTest:108-118。
        lenient().when(threePhaseTemplate.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            ExecutionRequest request = inv.getArgument(0);
            Function<ExecutionRequest, WithdrawalRequestEntity> persist = inv.getArgument(1);
            Function<WithdrawalRequestEntity, OnChainResult> execute = inv.getArgument(2);
            BiConsumer<WithdrawalRequestEntity, OnChainResult> confirm = inv.getArgument(3);
            WithdrawalRequestEntity record = persist.apply(request);
            OnChainResult result = execute.apply(record);
            confirm.accept(record, result);
            return record;
        });

        approvalPolicy = new DefaultApprovalPolicy(whitelistEntryRepository);
        inlineService = new DefaultWithdrawalApprovalService(
                approvalPolicy, signingServiceClient, PLATFORM_WALLET,
                withdrawalRequestRepository, withdrawalApproverRepository);
        templateService = new DefaultWithdrawalApprovalService(
                approvalPolicy, signingServiceClient, PLATFORM_WALLET,
                withdrawalRequestRepository, withdrawalApproverRepository, threePhaseTemplate);
    }

    // ==================== 用例 1：大额 2001 ⇒ 停在 APPROVED（核心回归） ====================

    @Test
    @DisplayName("大额 2001（inline）：提现停在 APPROVED 而非 FAILED，approvalId 落库")
    void largeAmount2001_keepsApprovedAndPersistsApprovalId_inlinePath() {
        when(signingServiceClient.signTransfer(any(), any(), any()))
                .thenReturn(approvalRequiredResp(APPROVAL_ID));

        WithdrawalRequest request = inlineService.requestWithdrawal(
                WHITELISTED_ADDR, THRESHOLD_AMOUNT, "NEX");
        // 金额恰好 10000：wallet 侧 1 个审批人即可放行（而 signing 侧已需人工审批）
        assertEquals(Integer.valueOf(1), request.getRequiredApprovers(),
                "金额 10000 在 wallet 侧应只需 1 个审批人（<= SMALL_THRESHOLD）");
        inlineService.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(request.getRequestId());

        // 缺陷本体：修复前这里是 FAILED
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus(),
                "2001 是「等审批」不是「签名失败」，绝不能置 FAILED");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, result.getStatus(),
                "应回到 APPROVED（已批准待执行），等待推进腿继续");
        assertNull(result.getRejectionReason(), "待审批不是失败，不得写 rejectionReason");
        assertNull(result.getChainTxHash(), "尚未签名，不应有 txHash");
        assertNull(result.getExecutedAt(), "尚未执行，不应有 executedAt");

        WithdrawalRequestEntity entity = requestStore.get(request.getRequestId());
        assertEquals(APPROVAL_ID, entity.getSigningApprovalId(), "approvalId 必须落库供推进腿复用");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, entity.getStatus());
        assertNull(entity.getRejectionReason());
        assertNull(entity.getSigningExecutionAttemptedAt(),
                "只是转审批、尚未发起执行，不得写 attemptedAt");

        verify(signingServiceClient, times(1)).signTransfer(any(), any(), any());
    }

    // ==================== 用例 2：小额 2000 + txHash ⇒ EXECUTED（对照组） ====================

    @Test
    @DisplayName("小额 2000 + txHash（inline）：仍正常 EXECUTED，签名路径未被改坏")
    void smallAmount2000WithTxHash_setsExecuted_inlinePath() {
        when(signingServiceClient.signTransfer(any(), any(), any())).thenReturn(okResp(TX_HASH));

        WithdrawalRequest request = inlineService.requestWithdrawal(
                WHITELISTED_ADDR, new BigDecimal("500"), "NEX");
        inlineService.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, result.getStatus());
        assertEquals(TX_HASH, result.getChainTxHash());
        assertNotNull(result.getExecutedAt());
        assertNull(result.getRejectionReason());

        WithdrawalRequestEntity entity = requestStore.get(request.getRequestId());
        assertEquals(TX_HASH, entity.getChainTxHash());
        assertNull(entity.getSigningApprovalId(), "直接签名成功不应写 approvalId");
    }

    // ==================== 用例 3：2001 缺 approvalId ⇒ FAILED（fail-closed） ====================

    @Test
    @DisplayName("2001 但缺 approvalId（inline）：无法跟踪 ⇒ FAILED，不得当待审批或 unverified")
    void approvalRequiredWithoutApprovalId_setsFailed_inlinePath() {
        when(signingServiceClient.signTransfer(any(), any(), any()))
                .thenReturn(approvalRequiredWithoutIdResp());

        WithdrawalRequest request = inlineService.requestWithdrawal(
                WHITELISTED_ADDR, THRESHOLD_AMOUNT, "NEX");
        inlineService.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus(),
                "缺 approvalId 无法推进，必须 fail-closed 判失败");
        assertNotNull(result.getRejectionReason());
        assertTrue(result.getRejectionReason().contains("approvalId"),
                "失败原因应指明缺 approvalId: " + result.getRejectionReason());
        // 这是「确定未动账」的失败，绝不能标成「钱可能已出去」
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "缺 approvalId 属确定失败，不应标成 EXECUTED_UNVERIFIED");

        WithdrawalRequestEntity entity = requestStore.get(request.getRequestId());
        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, entity.getStatus());
        assertNull(entity.getSigningApprovalId(), "无法跟踪的响应不得写入 approvalId");
    }

    // ==================== 用例 4：已有 approvalId 时重入 ⇒ 不再调 signTransfer（幂等） ====================

    @Test
    @DisplayName("重入（已有 signingApprovalId）：不再调 signTransfer，只查审批状态")
    void reentryWithExistingApprovalId_doesNotCallSignTransfer() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("PENDING"));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        // 最关键的一条断言：重入绝不能再调 /transfers/sign，否则每推进一次就多一条悬空审批
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
        verify(signingServiceClient, times(1)).getApprovalStatus(APPROVAL_ID);
        verify(signingServiceClient, never()).executeApproved(any());

        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, result.getStatus(),
                "签名侧仍是 PENDING ⇒ 继续等待，保持 APPROVED");
        assertEquals(APPROVAL_ID, requestStore.get(entity.getRequestId()).getSigningApprovalId(),
                "重入不得覆盖已存在的 approvalId");
    }

    // ==================== 用例 5：审批通过后推进成功 ⇒ EXECUTED ====================

    @Test
    @DisplayName("推进成功：审批 APPROVED 且无既往执行 ⇒ 调 executeApproved 并 EXECUTED")
    void resumeWhenApproved_executesAndSetsExecuted() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("APPROVED"));
        when(signingServiceClient.executeApproved(APPROVAL_ID)).thenReturn(okResp(TX_HASH));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, result.getStatus());
        assertEquals(TX_HASH, result.getChainTxHash());
        assertNotNull(result.getExecutedAt());

        verify(signingServiceClient, times(1)).executeApproved(APPROVAL_ID);
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());

        WithdrawalRequestEntity saved = requestStore.get(entity.getRequestId());
        assertEquals(TX_HASH, saved.getChainTxHash());
        assertNotNull(saved.getSigningExecutionAttemptedAt(),
                "发起过执行必须留痕，供后续防双花判定");
    }

    // ==================== 用例 6：防双花 ⇒ EXECUTED_UNVERIFIED（不是 FAILED） ====================

    @Test
    @DisplayName("防双花：已发起过执行却又看到 APPROVED ⇒ EXECUTED_UNVERIFIED，且不再 executeApproved")
    void resumeWhenApprovedAfterPriorAttempt_marksExecutedUnverifiedWithoutExecuting() {
        // 模拟「上次已发起执行、响应丢失，signing 侧因 TTL 超时回退到 APPROVED」
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(
                APPROVAL_ID, LocalDateTime.now().minusMinutes(5));
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("APPROVED"));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        // 关键：不是 FAILED。钱可能已出去，记 FAILED 会让运维按「没出账」处置并重复发起
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "可能已广播过 ⇒ 必须落 EXECUTED_UNVERIFIED 交人工，不能记成确定失败");
        assertNotNull(result.getRejectionReason(), "必须留下诊断原因供人工核对");
        // 方案 A 之后：区分靠枚举值，不靠字符串前缀。防止有人退回「FAILED + 前缀」的旧编码
        assertFalse(result.getRejectionReason().contains("EXECUTED_UNVERIFIED"),
                "rejectionReason 应是 classify 的 message 原文，不得再塞状态名前缀: "
                        + result.getRejectionReason());

        // 绝不重试执行，否则二次放款
        verify(signingServiceClient, never()).executeApproved(any());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    // ==================== 用例 7/8：5000 与 5001 ⇒ 清空 attemptedAt，可安全重试 ====================

    @Test
    @DisplayName("executeApproved 返 5000（确定未动账）：清空 attemptedAt，下一轮仍可执行")
    void resumeWhenExecuteApprovedReturns5000_clearsAttemptedAtAndRemainsRetryable() {
        assertRetryableAfterDefiniteNonExecution(
                Map.of("statusCode", 5000, "message", "approval not granted"), "5000");
    }

    @Test
    @DisplayName("executeApproved 返 5001（并发被抢，确定未动账）：清空 attemptedAt，下一轮仍可执行")
    void resumeWhenExecuteApprovedReturns5001_clearsAttemptedAtAndRemainsRetryable() {
        assertRetryableAfterDefiniteNonExecution(
                Map.of("statusCode", 5001, "message", "concurrent execution lost the claim"), "5001");
    }

    /**
     * 5000/5001 的共同契约：<b>确定未动账</b> ⇒ 必须清空 {@code signingExecutionAttemptedAt}，
     * 否则下一轮会被防双花分支误判为「可能已广播」而永久判死。
     *
     * <p>这是本次修复最容易写错的地方：{@code attemptedAt} 若只写不清，一次良性的
     * 「还没批完」就能让一笔从未广播的提现死掉 —— 正是本轮要消灭的缺陷换了个入口。</p>
     */
    private void assertRetryableAfterDefiniteNonExecution(Map<String, Object> executeApprovedResp,
                                                          String label) {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("APPROVED"));
        when(signingServiceClient.executeApproved(APPROVAL_ID)).thenReturn(executeApprovedResp);

        // 第 1 轮：确定未动账 ⇒ 钱没出去，单据必须保持可推进
        WithdrawalRequest first = inlineService.executeApprovedWithdrawal(entity.getRequestId());
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, first.getStatus(),
                label + " 是「确定未动账」不是「失败」，应继续等待");
        assertNull(requestStore.get(entity.getRequestId()).getSigningExecutionAttemptedAt(),
                label + " 确定未动账，必须清空 attemptedAt，否则下一轮会被防双花误杀");
        assertEquals(APPROVAL_ID, requestStore.get(entity.getRequestId()).getSigningApprovalId(),
                label + " 之后仍须保留 approvalId（推进腿要继续用同一个）");

        // 第 2 轮：审批通过 ⇒ 必须能正常执行，不得因上一轮的未完成被判死
        when(signingServiceClient.executeApproved(APPROVAL_ID)).thenReturn(okResp(TX_HASH));
        WithdrawalRequest second = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, second.getStatus(),
                "上一轮 " + label + " 不该让这笔提现永久失败");
        assertEquals(TX_HASH, second.getChainTxHash());
    }

    // ==================== 用例 9：executeApproved 结果不明 ⇒ EXECUTED_UNVERIFIED ====================

    @Test
    @DisplayName("executeApproved 返 null（结果不明）⇒ EXECUTED_UNVERIFIED，且保留 attemptedAt 防重试")
    void resumeWhenExecuteApprovedResultUnknown_marksExecutedUnverified() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("APPROVED"));
        // 降级 / 网络失败：结果不明，钱可能已经出去了
        when(signingServiceClient.executeApproved(APPROVAL_ID)).thenReturn(null);

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "结果不明不能写回 APPROVED（下次会被当可重试 → 二次放款），也不能记 FAILED");
        assertNotNull(result.getRejectionReason());
        assertFalse(result.getRejectionReason().contains("EXECUTED_UNVERIFIED"),
                "区分靠枚举值，不靠字符串前缀: " + result.getRejectionReason());

        WithdrawalRequestEntity saved = requestStore.get(entity.getRequestId());
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, saved.getStatus(),
                "实体状态同样不得停在 APPROVED");
        assertNotNull(saved.getSigningExecutionAttemptedAt(),
                "结果不明必须保留 attemptedAt —— 这是下一轮防双花的唯一依据");
    }

    // ==================== 用例 10：模板路径的 awaitingApproval 分支 ====================

    @Test
    @DisplayName("大额 2001（template）：三阶段模板路径同样停在 APPROVED，两路径不分叉")
    void largeAmount2001_keepsApprovedAndPersistsApprovalId_templatePath() {
        when(signingServiceClient.signTransfer(any(), any(), any()))
                .thenReturn(approvalRequiredResp(APPROVAL_ID));

        WithdrawalRequest request = templateService.requestWithdrawal(
                WHITELISTED_ADDR, THRESHOLD_AMOUNT, "NEX");
        templateService.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest result = templateService.executeApprovedWithdrawal(request.getRequestId());

        // 专抓「phase2 改对、phase3 漏改」：phase3 若缺 awaiting 分支，会落进 else 被判 FAILED。
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, result.getStatus(),
                "phase3 必须识别 awaitingApproval，否则会吞回 FAILED");
        assertNull(result.getRejectionReason());

        WithdrawalRequestEntity entity = requestStore.get(request.getRequestId());
        assertEquals(APPROVAL_ID, entity.getSigningApprovalId());
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, entity.getStatus());
        assertNull(entity.getRejectionReason());

        verify(signingServiceClient, times(1)).signTransfer(any(), any(), any());
    }

    // ==================== 用例 11：状态查不到 ⇒ fail-safe 保持 APPROVED ====================

    @Test
    @DisplayName("推进时审批状态查不到（降级返回 null）：保持 APPROVED，不判失败")
    void resumeWithUnavailableStatus_staysApproved() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID)).thenReturn(null);

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, result.getStatus(),
                "状态未知应继续等待（下一轮还能再试），不能判失败把单据做死");
        verify(signingServiceClient, never()).executeApproved(any());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
        assertNull(requestStore.get(entity.getRequestId()).getSigningExecutionAttemptedAt(),
                "没发起执行，不得写 attemptedAt");
    }

    // ==================== 用例 12：超时的 PENDING 审批 ⇒ FAILED（否则永久等待） ====================

    /**
     * 超时的 {@code PENDING} 审批必须收尾为 FAILED，否则提现会<b>永久等待</b>。
     *
     * <p><b>输入可达性（写这条用例前必须确认的事）</b>：signing 侧
     * {@code SigningApprovalRequest.isExpired()} 的实现是
     * {@code status == Status.PENDING && now > deadline}，而
     * {@code SigningApprovalController} 用 {@code data.put("expired", request.isExpired())}
     * 产出该字段 ⇒ <b>{@code expired=true} 只可能伴随 {@code status=PENDING}</b>。</p>
     *
     * <p>所以本用例用 <b>PENDING + expired=true</b>（真实可达），而<b>不是</b>
     * APPROVED / EXECUTED + expired（那两种组合线上不可能出现——EXECUTED 那种见 #18，
     * 已标注为防御性不变量）。</p>
     *
     * <p>为什么这道闸门重要：若没有它，{@code status=PENDING} 会落到 {@code switch} 的
     * {@code default} 分支 → {@code awaitingApproval} → 提现无限期停在 APPROVED，
     * 而 signing 侧那条审批永远批不够、也没人会来推进。这是"永久等待"fail-safe
     * <b>唯一的自愈出口</b>。</p>
     */
    @Test
    @DisplayName("审批 status=PENDING 且 expired=true（超时未批够）⇒ FAILED，绝不执行")
    void resumeWhenPendingButExpired_failsWithoutExecuting() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("PENDING", true));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus(),
                "超时未批够的审批不得继续等待，必须收尾为 FAILED，否则提现永久停在 APPROVED");
        assertTrue(result.getRejectionReason().toLowerCase().contains("expir"),
                "失败原因应指明过期: " + result.getRejectionReason());
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "过期 = 确定未动账，不得标成「钱可能已出去」");

        // 最关键：过期的绝不能执行
        verify(signingServiceClient, never()).executeApproved(any());
        // 行为判别器（与 #14/#15 同口径）：旧逻辑不查审批状态。
        // 没有这一行，本用例的判别力就只剩 contains("expir") 这个文案子串，太脆。
        verify(signingServiceClient, times(1)).getApprovalStatus(APPROVAL_ID);
    }

    /**
     * {@code expired=true} 只否掉「还没执行」的请求，<b>不能</b>否掉「已经执行」的。
     *
     * <p><b>这是一条防御性用例，不是当前可触发路径。</b>按 signing 侧
     * {@code SigningApprovalRequest.isExpired()} 的实现
     * （{@code status == PENDING && now > deadline}），{@code EXECUTED} 与
     * {@code expired=true} 在真实服务里不会同时出现——本用例通过 mock 构造这个组合，
     * 只为锁住「过期判断必须排除已执行」这个不变量。</p>
     *
     * <p>价值在于防止将来 {@code isExpired()} 的语义扩展（例如改成"任何状态超 TTL 都算过期"）
     * 时，把一笔已确认执行的提现降级成 FAILED —— 运维按"没出账"处置后重复发起，
     * 就是二次放款。<b>不要把本用例当作"线上会走到这里"的证据。</b></p>
     */
    @Test
    @DisplayName("[防御性/当前不可达] status=EXECUTED 且 expired=true ⇒ 仍是 EXECUTED_UNVERIFIED")
    void resumeWhenSigningReportsExecutedWithExpiredFlag_stillMarksExecutedUnverified() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        // 刻意不走 approvalStatusResp(...) helper：那里有防护，会拒绝构造这个不可达组合。
        // 本用例的目的正是锁住"万一契约变更"时的不变量，故显式构造并在此说明理由。
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(Map.of("statusCode", 2000, SigningResponses.KEY_DATA,
                        Map.of("status", "EXECUTED", "expired", true)));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "signing 已确认执行 ⇒ 钱已出，过期标记不得把它降级成 FAILED");
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus());

        verify(signingServiceClient, times(1)).getApprovalStatus(APPROVAL_ID);
        verify(signingServiceClient, never()).executeApproved(any());
    }

    // ==================== 用例 13-16：resumeSigning switch 其余四个分支 ====================

    /**
     * signing 侧报 {@code EXECUTED} = <b>钱已经出去了</b>，但我们没拿到交易哈希。
     *
     * <p>必须落 {@code EXECUTED_UNVERIFIED}，不能记 {@code FAILED}——
     * 后者会让运维按"没出账"处置并重复发起，造成二次放款。</p>
     */
    @Test
    @DisplayName("signing 报 EXECUTED（钱已出但无哈希）⇒ EXECUTED_UNVERIFIED，且不再 executeApproved")
    void resumeWhenSigningReportsExecuted_marksExecutedUnverified() {
        WithdrawalRequest result = advanceWithSigningStatus("EXECUTED");

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "signing 说已执行 ⇒ 钱可能已出，必须人工核对，不能当确定失败");
        assertNotNull(result.getRejectionReason());
        assertFalse(result.getRejectionReason().contains("EXECUTED_UNVERIFIED"),
                "区分靠枚举值，不靠字符串前缀: " + result.getRejectionReason());

        verify(signingServiceClient, never()).executeApproved(any());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    /**
     * signing 侧审批 {@code EXPIRED}：确定未动账的终态。
     *
     * <p>断言<b>不得</b>标成 {@code EXECUTED_UNVERIFIED}：过期意味着压根没执行过，
     * 若被染成"钱可能已出"，人工核对队列会被噪声淹没。</p>
     *
     * <p><b>判别力说明（勿"简化"）</b>：本用例的「status==FAILED」与「不是 UNVERIFIED」
     * 两条断言，在修复前的世界里<b>都成立</b>（旧逻辑调 signTransfer 拿 null → FAILED）。
     * 唯一判别力来自 {@link #advanceWithSigningStatus(String)} 内的
     * {@code verify(times(1)).getApprovalStatus(..)}——旧逻辑根本不查审批状态。</p>
     */
    @Test
    @DisplayName("signing 报 EXPIRED（审批超时）⇒ FAILED，且不标 EXECUTED_UNVERIFIED")
    void resumeWhenSigningReportsExpired_failsWithoutUnverifiedTag() {
        WithdrawalRequest result = advanceWithSigningStatus("EXPIRED");

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus(),
                "审批过期是终态，必须收尾为 FAILED，不能永久停在 APPROVED");
        assertNotNull(result.getRejectionReason());
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "审批过期 = 确定未动账，不得标成 EXECUTED_UNVERIFIED: "
                        + result.getRejectionReason());

        verify(signingServiceClient, never()).executeApproved(any());
    }

    /**
     * signing 侧审批被 {@code REJECTED}：正常终态，确定未动账。
     *
     * <p><b>判别力说明（勿"简化"）</b>：与 EXPIRED 同理，判别力来自
     * {@link #advanceWithSigningStatus(String)} 内的 {@code getApprovalStatus} 调用断言。</p>
     */
    @Test
    @DisplayName("signing 报 REJECTED（人工拒绝）⇒ FAILED，且不标 EXECUTED_UNVERIFIED")
    void resumeWhenSigningReportsRejected_failsWithoutUnverifiedTag() {
        WithdrawalRequest result = advanceWithSigningStatus("REJECTED");

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus());
        assertNotNull(result.getRejectionReason());
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "人工拒绝 = 确定未动账，不得标成 EXECUTED_UNVERIFIED: "
                        + result.getRejectionReason());

        verify(signingServiceClient, never()).executeApproved(any());
    }

    /**
     * signing 侧 {@code EXECUTING}（另一路正在执行）：结果未定，继续等。
     *
     * <p>与 {@code EXECUTED} 的区别：EXECUTING 是"可能正在动账"，此时<b>不写</b>
     * {@code attemptedAt}（我们没发起过），也不得调 {@code executeApproved}（会与对方撞车）。</p>
     */
    @Test
    @DisplayName("signing 报 EXECUTING（对方执行中）⇒ 保持 APPROVED 等待，不 executeApproved")
    void resumeWhenSigningReportsExecuting_staysApproved() {
        WithdrawalRequest result = advanceWithSigningStatus("EXECUTING");

        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, result.getStatus(),
                "对方正在执行 ⇒ 继续等，不能判失败也不能抢先执行");
        assertNull(result.getRejectionReason());

        verify(signingServiceClient, never()).executeApproved(any());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
        assertNull(requestStore.get(result.getRequestId()).getSigningExecutionAttemptedAt(),
                "我们没发起执行，不得写 attemptedAt");
    }

    /**
     * {@code executeApproved} 本身抛异常（网络中断 / Feign 异常 / 序列化失败）⇒ 结果不明。
     *
     * <p>这是 R1 修复的核心分支：请求<b>已经发出去了</b>，钱可能已经出去，只是响应没回来。
     * 若不 catch 而让它冒泡，三阶段模板的阶段2 会把异常统一转成
     * {@code OnChainResult.failure} ⇒ 落 {@code FAILED} ⇒ 运维按"没出账"处置并重复发起
     * ⇒ <b>二次放款</b>。</p>
     *
     * <p>判别力：修复前根本不调 {@code executeApproved}，因此不会抛，状态是 FAILED ⇒
     * 本用例红。不依赖任何文案。</p>
     */
    @Test
    @DisplayName("executeApproved 抛 RuntimeException（结果不明）⇒ EXECUTED_UNVERIFIED，不记 FAILED")
    void resumeWhenExecuteApprovedThrows_marksExecutedUnverified() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp("APPROVED"));
        // 注意：必须用 RuntimeException——实现里 catch 的是 RuntimeException，受检异常编译不过
        when(signingServiceClient.executeApproved(APPROVAL_ID))
                .thenThrow(new RuntimeException("boom"));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "执行请求已发出但结果不明 ⇒ 钱可能已出，不能记成确定失败");
        assertNotNull(result.getRejectionReason());
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus());

        // attemptedAt 必须保留：下一轮不能再自动重试
        assertNotNull(requestStore.get(entity.getRequestId()).getSigningExecutionAttemptedAt(),
                "结果不明时必须保留 attemptedAt，否则下一轮会被当可重试而二次放款");

        verify(signingServiceClient, times(1)).executeApproved(APPROVAL_ID);
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    // ==================== 辅助方法 ====================

    /**
     * 种一笔「已 APPROVED + 已有 approvalId + 从未发起执行」的提现，并让推进腿看到
     * 指定的 signing 审批状态，返回推进结果。
     *
     * <p><b>本方法内含一条判别性断言，勿删</b>：{@code verify(times(1)).getApprovalStatus(..)}。
     * 原因：EXPIRED / REJECTED 两条用例的「status==FAILED」「不是 UNVERIFIED」断言，
     * 在<b>修复前的世界</b>里<b>都成立</b>（旧逻辑调 {@code signTransfer} 拿 null → FAILED，
     * 且从不查审批状态）。唯一能区分两个世界的信号是「推进腿有没有去查审批状态」。
     * 删掉它，那两条立刻变成假绿。</p>
     *
     * <p>刻意不用「失败原因包含 expired/rejected」这类文案断言来加强判别力：
     * 那会把判别力压在自由格式字符串上；{@code getApprovalStatus} 的调用次数是行为事实，
     * 不随文案漂移。</p>
     *
     * @param signingStatus {@code getApprovalStatus} 返回的 {@code data.status}
     */
    // ==================== 用例 19：查询审批状态抛异常 ⇒ 保持等待 ====================

    /**
     * {@code getApprovalStatus} 抛异常（Feign 在非 2xx 时抛 FeignException，fallback 只在
     * 熔断降级时生效）时，钱<b>一定没动</b> —— 保持等待，不能判失败。
     *
     * <p>若不兜住，异常会冒泡到三阶段模板的 catch 被记成 FAILED，把一笔本可继续等待的
     * 提现永久判死。</p>
     */
    @Test
    @DisplayName("getApprovalStatus 抛异常（查询失败）⇒ 保持 APPROVED 等待，不判失败")
    void resumeWhenGetApprovalStatusThrows_staysApproved() {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenThrow(new RuntimeException("feign 500"));

        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, result.getStatus(),
                "查询失败不代表钱动了 ⇒ 必须保持等待，不能判失败");
        // 没走到发起执行那步，attemptedAt 必须仍为空（否则下一轮会被误判成「可能已广播」）
        assertNull(withdrawalRequestRepository.findByRequestId(entity.getRequestId())
                        .orElseThrow().getSigningExecutionAttemptedAt(),
                "查询失败时不得写 attemptedAt");
        verify(signingServiceClient, never()).executeApproved(any());
    }

    private WithdrawalRequest advanceWithSigningStatus(String signingStatus) {
        WithdrawalRequestEntity entity = seedApprovedWithApprovalId(APPROVAL_ID, null);
        when(signingServiceClient.getApprovalStatus(APPROVAL_ID))
                .thenReturn(approvalStatusResp(signingStatus));
        WithdrawalRequest result = inlineService.executeApprovedWithdrawal(entity.getRequestId());
        // 判别性断言（详见方法 javadoc）：推进腿必须查询审批状态，旧逻辑不会。
        verify(signingServiceClient, times(1)).getApprovalStatus(APPROVAL_ID);
        return result;
    }

    /**
     * 直接种一笔「已 APPROVED 且已带 signingApprovalId」的提现到模拟表里。
     *
     * <p>直接种而不是「先跑一次 2001 再重入」，是为了让 {@code verify(never()).signTransfer}
     * 断言有意义 —— 先跑一次会真的调用一次 signTransfer，never() 就不成立了。</p>
     *
     * @param approvalId  签名侧审批请求 ID
     * @param attemptedAt 既往执行尝试时间（防双花用例传非 null）
     */
    private WithdrawalRequestEntity seedApprovedWithApprovalId(String approvalId,
                                                               LocalDateTime attemptedAt) {
        WithdrawalRequestEntity entity = new WithdrawalRequestEntity();
        entity.setRequestId("WD-seeded-" + System.nanoTime());
        entity.setToAddress(WHITELISTED_ADDR);
        entity.setAmount(THRESHOLD_AMOUNT);
        entity.setCurrency("NEX");
        entity.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
        entity.setRequiredApprovers(1);
        entity.setApprovedCount(1);
        entity.setSigningApprovalId(approvalId);
        entity.setSigningExecutionAttemptedAt(attemptedAt);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        requestStore.put(entity.getRequestId(), entity);
        return entity;
    }

    /** signTransfer 成功响应（2000 + 非空 data）。 */
    private static Map<String, Object> okResp(String txHash) {
        return Map.of("statusCode", 2000, "data", txHash);
    }

    /** signTransfer 大额待审批响应（2001 + approvalId，无 data）。 */
    private static Map<String, Object> approvalRequiredResp(String approvalId) {
        return Map.of("statusCode", 2001, "approvalId", approvalId,
                "message", "large amount requires manual approval");
    }

    /** signTransfer 待审批但缺 approvalId（无法跟踪 ⇒ 应 fail-closed）。 */
    private static Map<String, Object> approvalRequiredWithoutIdResp() {
        return Map.of("statusCode", 2001, "message", "approval required");
    }

    /** getApprovalStatus 响应信封：{@code {statusCode:2000, data:{status:...}}}（未过期）。 */
    private static Map<String, Object> approvalStatusResp(String status) {
        return approvalStatusResp(status, false);
    }

    /**
     * getApprovalStatus 响应信封，可显式指定 {@code data.expired}。
     *
     * <p><b>防护（不要删）</b>：真实契约下 {@code expired=true} <b>只可能</b>伴随
     * {@code status=PENDING}——signing 侧 {@code SigningApprovalRequest.isExpired()} 的实现是
     * {@code status == PENDING && now > deadline}，而 {@code SigningApprovalController} 直接
     * {@code data.put("expired", request.isExpired())}。取值空间不是笛卡尔积。</p>
     *
     * <p>这里主动抛异常而不是静默放行，是为了让"构造了线上不可能出现的组合"在<b>运行期立刻暴露</b>，
     * 而不是靠 review 发现。本类曾两次踩这个坑（APPROVED+expired、EXECUTED+expired）。
     * 确需构造不可达组合（如防御性用例）时，请<b>直接构造 Map</b> 并在注释里说明理由，
     * 不要绕过本防护。</p>
     */
    private static Map<String, Object> approvalStatusResp(String status, boolean expired) {
        if (expired && !"PENDING".equals(status)) {
            throw new IllegalArgumentException(
                    "真实契约下 expired=true 仅可能伴随 status=PENDING"
                            + "（SigningApprovalRequest.isExpired = status==PENDING && now>deadline），"
                            + "当前 status=" + status
                            + "。要构造不可达组合请直接构造 Map 并注明理由。");
        }
        return Map.of("statusCode", 2000, SigningResponses.KEY_DATA,
                Map.of("status", status, "expired", expired));
    }
}
