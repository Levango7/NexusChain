package org.nexus.gateway.reconciliation.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;
import org.nexus.gateway.reconciliation.SuspenseAccount;
import org.nexus.gateway.reconciliation.SuspenseAccountRepository;
import org.nexus.gateway.reconciliation.compensation.CompensationRecord;
import org.nexus.gateway.reconciliation.compensation.CompensationRecordRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReconciliationReportService 单元测试。
 */
class ReconciliationReportServiceTest {

    private ReconciliationReportRecordRepository reportRepository;
    private ReconciliationDiscrepancyRepository discrepancyRepository;
    private CompensationRecordRepository compensationRepository;
    private SuspenseAccountRepository suspenseRepository;
    private ReconciliationReportService service;

    private static final Long MERCHANT_ID = 500L;
    private static final LocalDate REPORT_DATE = LocalDate.of(2026, 9, 27);

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReconciliationReportRecordRepository.class);
        discrepancyRepository = mock(ReconciliationDiscrepancyRepository.class);
        compensationRepository = mock(CompensationRecordRepository.class);
        suspenseRepository = mock(SuspenseAccountRepository.class);
        service = new ReconciliationReportService(
                reportRepository, discrepancyRepository, compensationRepository,
                suspenseRepository, new ObjectMapper());
    }

    // ==================== 报表生成 ====================

    @Test
    @DisplayName("generateReport — 生成包含差异和补偿的报表")
    void generateReportWithDiscrepanciesAndCompensations() {
        // 准备差错数据
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(1L);
        discrepancy.setMerchantId(MERCHANT_ID);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        discrepancy.setTransactionId("ORD-001");
        discrepancy.setAmountDiff(new BigDecimal("1.00"));
        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.findByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of(discrepancy));

        // 准备补偿数据
        CompensationRecord compensation = new CompensationRecord();
        compensation.setId(1L);
        compensation.setDiscrepancyId(1L);
        compensation.setMerchantId(MERCHANT_ID);
        compensation.setCompensationType(CompensationRecord.CompensationType.INTERNAL_ADJUST);
        compensation.setAmount(new BigDecimal("1.00"));
        compensation.setStatus(CompensationRecord.CompensationStatus.SUCCESS);

        when(compensationRepository.findByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of(compensation));

        // 准备挂账数据
        when(suspenseRepository.findByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of());

        // 保存报表时返回带 ID 的记录
        when(reportRepository.save(any())).thenAnswer(inv -> {
            ReconciliationReportRecord saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        ReconciliationReportRecord result = service.generateReport(
                MERCHANT_ID, REPORT_DATE, "WECHAT");

        assertNotNull(result.getId());
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertEquals(REPORT_DATE, result.getReportDate());
        assertEquals("WECHAT", result.getChannelType());
        assertEquals("JSON", result.getFileFormat());
        assertEquals(1, result.getDiscrepancyCount());
        assertEquals(1, result.getCompensationCount());
        assertEquals(0, result.getSuspenseCount());
        assertEquals(new BigDecimal("1.00"), result.getTotalDiscrepancyAmount());
        verify(reportRepository).save(any());
    }

    @Test
    @DisplayName("generateReport — 无差异时生成空报表")
    void generateReportNoDiscrepancies() {
        when(discrepancyRepository.findByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of());
        when(compensationRepository.findByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of());
        when(suspenseRepository.findByMerchantIdAndCreatedAtBetween(
                eq(MERCHANT_ID), any(), any()))
                .thenReturn(List.of());
        when(reportRepository.save(any())).thenAnswer(inv -> {
            ReconciliationReportRecord saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        ReconciliationReportRecord result = service.generateReport(
                MERCHANT_ID, REPORT_DATE, null);

        assertNotNull(result.getId());
        assertEquals(0, result.getDiscrepancyCount());
        assertEquals(0, result.getCompensationCount());
        assertEquals(0, result.getSuspenseCount());
        assertEquals(BigDecimal.ZERO, result.getTotalDiscrepancyAmount());
        assertNull(result.getChannelType());
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("getReport — 查询存在的报表")
    void getReportFound() {
        ReconciliationReportRecord record = new ReconciliationReportRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);

        when(reportRepository.findById(1L)).thenReturn(Optional.of(record));

        Optional<ReconciliationReportRecord> result = service.getReport(1L);

        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
    }

    @Test
    @DisplayName("getReport — 查询不存在的报表返回空")
    void getReportNotFound() {
        when(reportRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<ReconciliationReportRecord> result = service.getReport(999L);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("getReportsByMerchant — 查询商户报表列表")
    void getReportsByMerchant() {
        ReconciliationReportRecord record1 = new ReconciliationReportRecord();
        record1.setId(1L);
        record1.setMerchantId(MERCHANT_ID);
        ReconciliationReportRecord record2 = new ReconciliationReportRecord();
        record2.setId(2L);
        record2.setMerchantId(MERCHANT_ID);

        when(reportRepository.findByMerchantIdOrderByGeneratedAtDesc(MERCHANT_ID))
                .thenReturn(List.of(record2, record1));

        List<ReconciliationReportRecord> results = service.getReportsByMerchant(MERCHANT_ID);

        assertEquals(2, results.size());
        assertEquals(2L, results.get(0).getId());
    }
}