package org.nexus.gateway.reconciliation.compensation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AutoCompensationService 真实模式执行回归测试（P0 修复 2026-10-11）。
 *
 * <p>背景：{@code executeRealCompensation} 此前直接抛 {@code UnsupportedOperationException}
 * （"差错 → 补偿"链路从未真正执行过）。本测试钉住：</p>
 * <ol>
 *   <li>INTERNAL_ADJUST 走真实账本调账，方向与 {@code ReconciliationAdjustmentService} 权威映射一致
 *       （短款 → 扣账；金额不一致 → 按差额方向）；</li>
 *   <li>REFUND（长款）**显式拒绝** —— 同一差错类型已被自动调账服务按 CREDIT_ADJUST 处理，
 *       再走渠道退款会造成双向动账；</li>
 *   <li>账本服务缺失 → fail-closed（不静默成功）；</li>
 *   <li>sandbox 模式不受影响（仍为模拟执行）。</li>
 * </ol>
 */
class AutoCompensationRealExecTest {

    private CompensationRecordRepository compensationRepository;
    private ReconciliationDiscrepancyRepository discrepancyRepository;
    private AccountService accountService;

    private static final Long MERCHANT_ID = 500L;
    private static final Long RECORD_ID = 7L;
    private static final Long DISCREPANCY_ID = 42L;

    @BeforeEach
    void setUp() {
        compensationRepository = mock(CompensationRecordRepository.class);
        discrepancyRepository = mock(ReconciliationDiscrepancyRepository.class);
        accountService = mock(AccountService.class);
        when(compensationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AutoCompensationService service(AccountService acct, boolean sandbox) throws Exception {
        AutoCompensationService svc = acct == null
                ? new AutoCompensationService(compensationRepository, discrepancyRepository)
                : new AutoCompensationService(compensationRepository, discrepancyRepository, acct);
        setField(svc, "autoEnabled", true);
        setField(svc, "sandbox", sandbox);
        return svc;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private CompensationRecord pendingRecord(CompensationRecord.CompensationType type, BigDecimal amount) {
        CompensationRecord r = new CompensationRecord();
        r.setId(RECORD_ID);
        r.setDiscrepancyId(DISCREPANCY_ID);
        r.setMerchantId(MERCHANT_ID);
        r.setCompensationType(type);
        r.setAmount(amount);
        r.setStatus(CompensationRecord.CompensationStatus.PENDING);
        when(compensationRepository.findById(RECORD_ID)).thenReturn(Optional.of(r));
        return r;
    }

    private ReconciliationDiscrepancy discrepancy(ReconciliationDiscrepancy.DiscrepancyType type,
                                                   BigDecimal channel, BigDecimal internal) {
        ReconciliationDiscrepancy d = new ReconciliationDiscrepancy();
        d.setId(DISCREPANCY_ID);
        d.setMerchantId(MERCHANT_ID);
        d.setDiscrepancyType(type);
        d.setChannelAmount(channel);
        d.setInternalAmount(internal);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));
        return d;
    }

    // ==================== INTERNAL_ADJUST：真实调账 ====================

    @Test
    @DisplayName("真实模式 · 短款（内部多记）→ 扣账（withdrawWithType + RECON_ADJUST）")
    void realMode_shortAmount_debits() throws Exception {
        AutoCompensationService svc = service(accountService, false);
        discrepancy(ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT, null, new BigDecimal("100.00"));
        CompensationRecord record = pendingRecord(
                CompensationRecord.CompensationType.INTERNAL_ADJUST, new BigDecimal("100.00"));

        CompensationRecord result = svc.executeCompensation(RECORD_ID);

        assertEquals(CompensationRecord.CompensationStatus.SUCCESS, result.getStatus());
        verify(accountService).withdrawWithType(eq(MERCHANT_ID), eq(new BigDecimal("100.00")),
                eq("AUTO_COMP_" + RECORD_ID), eq(AccountOperationType.RECON_ADJUST));
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        assertNotNull(result.getAccountTransactionRef());
        assertNotEquals(record.getStatus(), CompensationRecord.CompensationStatus.FAILED);
    }

    @Test
    @DisplayName("真实模式 · 金额不一致（渠道>内部）→ 加钱（depositWithType）")
    void realMode_amountMismatch_channelHigher_credits() throws Exception {
        AutoCompensationService svc = service(accountService, false);
        discrepancy(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                new BigDecimal("100.00"), new BigDecimal("90.00"));
        pendingRecord(CompensationRecord.CompensationType.INTERNAL_ADJUST, new BigDecimal("10.00"));

        CompensationRecord result = svc.executeCompensation(RECORD_ID);

        assertEquals(CompensationRecord.CompensationStatus.SUCCESS, result.getStatus());
        verify(accountService).depositWithType(eq(MERCHANT_ID), eq(new BigDecimal("10.00")),
                eq("AUTO_COMP_" + RECORD_ID), eq(AccountOperationType.RECON_ADJUST));
    }

    @Test
    @DisplayName("真实模式 · 金额不一致（渠道<内部）→ 扣账")
    void realMode_amountMismatch_channelLower_debits() throws Exception {
        AutoCompensationService svc = service(accountService, false);
        discrepancy(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                new BigDecimal("90.00"), new BigDecimal("100.00"));
        pendingRecord(CompensationRecord.CompensationType.INTERNAL_ADJUST, new BigDecimal("10.00"));

        CompensationRecord result = svc.executeCompensation(RECORD_ID);

        assertEquals(CompensationRecord.CompensationStatus.SUCCESS, result.getStatus());
        verify(accountService).withdrawWithType(eq(MERCHANT_ID), eq(new BigDecimal("10.00")),
                eq("AUTO_COMP_" + RECORD_ID), eq(AccountOperationType.RECON_ADJUST));
    }

    // ==================== REFUND（长款）：显式拒绝，防双向动账 ====================

    @Test
    @DisplayName("真实模式 · 长款(REFUND) → 拒绝执行并说明原因（防与 CREDIT_ADJUST 双向动账）")
    void realMode_longAmountRefund_refusedToAvoidDoubleMovement() throws Exception {
        AutoCompensationService svc = service(accountService, false);
        discrepancy(ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("100.00"), null);
        CompensationRecord record = pendingRecord(
                CompensationRecord.CompensationType.REFUND, new BigDecimal("100.00"));

        CompensationRecord result = svc.executeCompensation(RECORD_ID);

        assertEquals(CompensationRecord.CompensationStatus.FAILED, result.getStatus());
        // 业务决策（2026-10-11）：长款走渠道退款；但前置未齐（无渠道归属字段 + 退款接口未接入）
        // 且需先与自动调账路径(CREDIT_ADJUST)互斥 → 现阶段 fail-closed 拒绝、零资金动作。
        assertTrue(result.getFailureReason().contains("渠道退款"), result.getFailureReason());
        assertTrue(result.getFailureReason().contains("缺前置"), result.getFailureReason());
        assertTrue(result.getFailureReason().contains("CREDIT_ADJUST"), result.getFailureReason());
        verifyNoInteractions(accountService);
        assertNull(result.getChannelRefundRef(), "不得留下任何渠道退款引用");
    }

    // ==================== fail-closed ====================

    @Test
    @DisplayName("真实模式 · 账本服务未注入 → fail-closed（FAILED，不静默成功）")
    void realMode_noAccountService_failsClosed() throws Exception {
        AutoCompensationService svc = service(null, false);
        discrepancy(ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT, null, new BigDecimal("100.00"));
        pendingRecord(CompensationRecord.CompensationType.INTERNAL_ADJUST, new BigDecimal("100.00"));

        CompensationRecord result = svc.executeCompensation(RECORD_ID);

        assertEquals(CompensationRecord.CompensationStatus.FAILED, result.getStatus());
        assertTrue(result.getFailureReason().contains("未注入"), result.getFailureReason());
        verifyNoInteractions(accountService);
    }

    // ==================== sandbox 不受影响 ====================

    @Test
    @DisplayName("sandbox 模式 → 仍为模拟执行，不触碰账本")
    void sandboxMode_stillSimulates() throws Exception {
        AutoCompensationService svc = service(accountService, true);
        discrepancy(ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT, null, new BigDecimal("100.00"));
        CompensationRecord record = pendingRecord(
                CompensationRecord.CompensationType.INTERNAL_ADJUST, new BigDecimal("100.00"));

        CompensationRecord result = svc.executeCompensation(RECORD_ID);

        assertEquals(CompensationRecord.CompensationStatus.SUCCESS, result.getStatus());
        verifyNoInteractions(accountService);
        assertTrue(result.getAccountTransactionRef().startsWith("SIMULATED_ADJ_"),
                "sandbox 应写模拟引用，实际=" + result.getAccountTransactionRef());
    }
}
