package org.nexus.gateway.reconciliation.compensation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("executeCompensation — sandbox 模式模拟执行成功")
    void executeCompensationSandboxSuccess() {
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

        assertEquals(CompensationRecord.CompensationStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getExecutedAt());
        assertNotNull(result.getChannelRefundRef());
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