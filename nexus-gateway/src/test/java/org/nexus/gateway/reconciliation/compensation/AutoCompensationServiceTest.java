package org.nexus.gateway.reconciliation.compensation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AutoCompensationService 单元测试。
 */
class AutoCompensationServiceTest {

    private CompensationRecordRepository compensationRepository;
    private ReconciliationDiscrepancyRepository discrepancyRepository;
    private AutoCompensationService service;

    private static final Long MERCHANT_ID = 500L;
    private static final Long DISCREPANCY_ID = 1L;

    @BeforeEach
    void setUp() throws Exception {
        compensationRepository = mock(CompensationRecordRepository.class);
        discrepancyRepository = mock(ReconciliationDiscrepancyRepository.class);
        service = new AutoCompensationService(compensationRepository, discrepancyRepository);

        // 通过反射设置 @Value 字段（单元测试无 Spring 上下文）
        setField(service, "autoEnabled", true);
        setField(service, "sandbox", true);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ==================== 创建补偿记录 ====================

    @Test
    @DisplayName("createCompensationForDiscrepancy — LONG_AMOUNT 创建 REFUND 补偿")
    void createCompensationForLongAmount() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                new BigDecimal("100.00"), null);

        when(compensationRepository.existsByDiscrepancyId(DISCREPANCY_ID)).thenReturn(false);
        when(compensationRepository.save(any())).thenAnswer(inv -> {
            CompensationRecord saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancy(discrepancy);

        assertTrue(result.isPresent());
        assertEquals(CompensationRecord.CompensationType.REFUND, result.get().getCompensationType());
        assertEquals(new BigDecimal("100.00"), result.get().getAmount());
        assertEquals(CompensationRecord.CompensationStatus.PENDING, result.get().getStatus());
    }

    @Test
    @DisplayName("createCompensationForDiscrepancy — SHORT_AMOUNT 创建 INTERNAL_ADJUST 补偿")
    void createCompensationForShortAmount() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT,
                null, new BigDecimal("200.00"));

        when(compensationRepository.existsByDiscrepancyId(DISCREPANCY_ID)).thenReturn(false);
        when(compensationRepository.save(any())).thenAnswer(inv -> {
            CompensationRecord saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancy(discrepancy);

        assertTrue(result.isPresent());
        assertEquals(CompensationRecord.CompensationType.INTERNAL_ADJUST, result.get().getCompensationType());
        assertEquals(new BigDecimal("200.00"), result.get().getAmount());
    }

    @Test
    @DisplayName("createCompensationForDiscrepancy — AMOUNT_MISMATCH 创建 INTERNAL_ADJUST 补偿")
    void createCompensationForAmountMismatch() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                new BigDecimal("100.00"), new BigDecimal("99.00"));
        discrepancy.setAmountDiff(new BigDecimal("1.00"));

        when(compensationRepository.existsByDiscrepancyId(DISCREPANCY_ID)).thenReturn(false);
        when(compensationRepository.save(any())).thenAnswer(inv -> {
            CompensationRecord saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancy(discrepancy);

        assertTrue(result.isPresent());
        assertEquals(CompensationRecord.CompensationType.INTERNAL_ADJUST, result.get().getCompensationType());
        assertEquals(new BigDecimal("1.00"), result.get().getAmount());
    }

    @Test
    @DisplayName("createCompensationForDiscrepancy — STATUS_MISMATCH 不自动补偿")
    void createCompensationForStatusMismatch() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.STATUS_MISMATCH,
                new BigDecimal("100.00"), new BigDecimal("100.00"));

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancy(discrepancy);

        assertTrue(result.isEmpty());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("createCompensationForDiscrepancy — 幂等：已有补偿记录时跳过")
    void createCompensationIdempotent() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                new BigDecimal("100.00"), null);

        when(compensationRepository.existsByDiscrepancyId(DISCREPANCY_ID)).thenReturn(true);

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancy(discrepancy);

        assertTrue(result.isEmpty());
        verify(compensationRepository, never()).save(any());
    }

    // ==================== 执行补偿 ====================

    @Test
    @DisplayName("executeCompensation — sandbox 模式记为 SIMULATED（不得记 SUCCESS）")
    void executeCompensationSandboxMarksSimulated() {
        CompensationRecord record = new CompensationRecord();
        record.setId(1L);
        record.setDiscrepancyId(DISCREPANCY_ID);
        record.setMerchantId(MERCHANT_ID);
        record.setCompensationType(CompensationRecord.CompensationType.REFUND);
        record.setAmount(new BigDecimal("100.00"));
        record.setStatus(CompensationRecord.CompensationStatus.PENDING);

        when(compensationRepository.findById(1L)).thenReturn(Optional.of(record));
        when(compensationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // sandbox 默认为 true，模拟执行
        CompensationRecord result = service.executeCompensation(1L);

        assertEquals(CompensationRecord.CompensationStatus.SIMULATED, result.getStatus());
        assertNotNull(result.getExecutedAt());
        assertNotNull(result.getChannelRefundRef());
    }

    @Test
    @DisplayName("executeCompensation — 模拟执行不闭环差错（SIMULATED 不算已处理）")
    void sandboxExecutionDoesNotResolveDiscrepancy() {
        CompensationRecord record = new CompensationRecord();
        record.setId(1L);
        record.setDiscrepancyId(DISCREPANCY_ID);
        record.setMerchantId(MERCHANT_ID);
        record.setCompensationType(CompensationRecord.CompensationType.REFUND);
        record.setAmount(new BigDecimal("100.00"));
        record.setStatus(CompensationRecord.CompensationStatus.PENDING);

        when(compensationRepository.findById(1L)).thenReturn(Optional.of(record));
        when(compensationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompensationRecord result = service.executeCompensation(1L);

        assertEquals(CompensationRecord.CompensationStatus.SIMULATED, result.getStatus());
        verify(discrepancyRepository, never()).save(any());
    }

    @Test
    @DisplayName("executeCompensation — 非 PENDING 状态跳过执行")
    void executeCompensationNotPending() {
        CompensationRecord record = new CompensationRecord();
        record.setId(1L);
        record.setStatus(CompensationRecord.CompensationStatus.SUCCESS);

        when(compensationRepository.findById(1L)).thenReturn(Optional.of(record));

        CompensationRecord result = service.executeCompensation(1L);

        assertEquals(CompensationRecord.CompensationStatus.SUCCESS, result.getStatus());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("executeCompensation — 不存在的 ID 抛异常")
    void executeCompensationNotFound() {
        when(compensationRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.executeCompensation(999L));
    }

    // ==================== 人工入口（2026-10-12 新增） ====================

    private void setCaps(String maxAmount, int maxCount, String maxTotal) throws Exception {
        setField(service, "autoMaxAmount", maxAmount == null ? null : new BigDecimal(maxAmount));
        setField(service, "dailyMaxCount", maxCount);
        setField(service, "dailyMaxTotal", maxTotal == null ? null : new BigDecimal(maxTotal));
    }

    @Test
    @DisplayName("人工入口 — 长款未超限：创建 REFUND 补偿")
    void manualEntryLongAmountCreatesRefund() throws Exception {
        setCaps("1000", 20, "10000");
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("300.00"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));
        when(compensationRepository.findByMerchantIdAndCreatedAtBetween(eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of());
        when(compensationRepository.existsByDiscrepancyId(DISCREPANCY_ID)).thenReturn(false);
        when(compensationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancyId(DISCREPANCY_ID);

        assertTrue(result.isPresent());
        assertEquals(CompensationRecord.CompensationType.REFUND, result.get().getCompensationType());
        assertEquals(new BigDecimal("300.00"), result.get().getAmount());
        assertEquals(CompensationRecord.CompensationStatus.PENDING, result.get().getStatus());
    }

    @Test
    @DisplayName("人工入口 — 短款被拒绝（防与自动调账对同一差错二次扣账）")
    void manualEntryRejectsShortAmount() throws Exception {
        setCaps("1000", 20, "10000");
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT, null, new BigDecimal("200.00"));
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancyId(DISCREPANCY_ID);

        assertTrue(result.isEmpty());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("人工入口 — 单笔超限：拒绝并转 MANUAL_REVIEW")
    void manualEntryRejectsOverSingleCap() throws Exception {
        setCaps("1000", 20, "10000");
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("1000.01"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancyId(DISCREPANCY_ID);

        assertTrue(result.isEmpty());
        assertEquals(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW, d.getResolutionType());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("人工入口 — 当日笔数超限：拒绝")
    void manualEntryRejectsOverDailyCount() throws Exception {
        setCaps("1000", 2, "10000");
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("100.00"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));
        when(compensationRepository.findByMerchantIdAndCreatedAtBetween(eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of(new CompensationRecord(), new CompensationRecord()));

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancyId(DISCREPANCY_ID);

        assertTrue(result.isEmpty());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("人工入口 — 当日累计超限：拒绝")
    void manualEntryRejectsOverDailyTotal() throws Exception {
        setCaps("1000", 20, "1000");
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("600.00"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));
        CompensationRecord old = new CompensationRecord();
        old.setAmount(new BigDecimal("500.00"));
        when(compensationRepository.findByMerchantIdAndCreatedAtBetween(eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of(old));

        Optional<CompensationRecord> result = service.createCompensationForDiscrepancyId(DISCREPANCY_ID);

        assertTrue(result.isEmpty());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("人工入口 — 已有补偿记录：幂等跳过")
    void manualEntryIdempotent() throws Exception {
        setCaps("1000", 20, "10000");
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("100.00"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));
        when(compensationRepository.findByMerchantIdAndCreatedAtBetween(eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of());
        when(compensationRepository.existsByDiscrepancyId(DISCREPANCY_ID)).thenReturn(true);

        assertTrue(service.createCompensationForDiscrepancyId(DISCREPANCY_ID).isEmpty());
        verify(compensationRepository, never()).save(any());
    }

    @Test
    @DisplayName("人工入口 — 差错不存在：返回空")
    void manualEntryMissingDiscrepancy() {
        when(discrepancyRepository.findById(999L)).thenReturn(Optional.empty());
        assertTrue(service.createCompensationForDiscrepancyId(999L).isEmpty());
    }

    @Test
    @DisplayName("人工入口 — 上限未配置（null/0）不拦截，避免默认 0 全拒")
    void manualEntryCapsUnsetDoesNotBlock() {
        // setUp 未设置上限字段：autoMaxAmount=null、dailyMaxCount=0、dailyMaxTotal=null
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("999999.00"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));
        when(compensationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertTrue(service.createCompensationForDiscrepancyId(DISCREPANCY_ID).isPresent());
    }

    @Test
    @DisplayName("merchantIdOfDiscrepancy — 返回差错归属商户")
    void merchantIdOfDiscrepancyReturnsOwner() {
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT, new BigDecimal("1.00"), null);
        when(discrepancyRepository.findById(DISCREPANCY_ID)).thenReturn(Optional.of(d));

        assertEquals(Optional.of(MERCHANT_ID), service.merchantIdOfDiscrepancy(DISCREPANCY_ID));
    }

    // ==================== 辅助方法 ====================

    private ReconciliationDiscrepancy createDiscrepancy(
            ReconciliationDiscrepancy.DiscrepancyType type,
            BigDecimal channelAmount, BigDecimal internalAmount) {
        ReconciliationDiscrepancy d = new ReconciliationDiscrepancy();
        d.setId(DISCREPANCY_ID);
        d.setMerchantId(MERCHANT_ID);
        d.setDiscrepancyType(type);
        d.setTransactionId("ORD-001");
        d.setChannelAmount(channelAmount);
        d.setInternalAmount(internalAmount);
        d.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        return d;
    }
}