package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DiscrepancyResolutionService 单元测试。
 *
 * <p>覆盖差错自动处置规则、状态流转（DISCOVERED → INVESTIGATING → RESOLVED/ESCALATED）、
 * 查询方法等核心场景。</p>
 */
class DiscrepancyResolutionServiceTest {

    private ReconciliationDiscrepancyRepository discrepancyRepository;
    private DiscrepancyResolutionService resolutionService;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        discrepancyRepository = mock(ReconciliationDiscrepancyRepository.class);
        resolutionService = new DiscrepancyResolutionService(discrepancyRepository);
        resolutionService.setAmountTolerance(new BigDecimal("0.01"));
    }

    // ==================== autoResolve ====================

    @Test
    @DisplayName("autoResolve — 金额容差范围内的 AMOUNT_MISMATCH 自动解决")
    void autoResolveAmountMismatchWithinTolerance() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        discrepancy.setAmountDiff(new BigDecimal("0.005"));

        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.autoResolve(discrepancy);

        assertEquals(ReconciliationDiscrepancy.ResolutionType.AUTO_RESOLVE, result.getResolutionType());
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, result.getStatus());
        assertNotNull(result.getResolutionNote());
        assertNotNull(result.getResolvedAt());
    }

    @Test
    @DisplayName("autoResolve — 金额超出容差的 AMOUNT_MISMATCH 标记为 MANUAL_REVIEW")
    void autoResolveAmountMismatchBeyondTolerance() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        discrepancy.setAmountDiff(new BigDecimal("5"));

        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.autoResolve(discrepancy);

        assertEquals(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW, result.getResolutionType());
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, result.getStatus());
        assertNull(result.getResolvedAt());
    }

    @Test
    @DisplayName("autoResolve — STATUS_MISMATCH 标记为 MANUAL_REVIEW")
    void autoResolveStatusMismatchIsManualReview() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.STATUS_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.autoResolve(discrepancy);

        assertEquals(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW, result.getResolutionType());
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, result.getStatus());
    }

    @Test
    @DisplayName("autoResolve — LONG_AMOUNT 标记为 PENDING_INVESTIGATION")
    void autoResolveLongAmountIsPendingInvestigation() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.autoResolve(discrepancy);

        assertEquals(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION,
                result.getResolutionType());
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, result.getStatus());
    }

    @Test
    @DisplayName("autoResolve — SHORT_AMOUNT 标记为 PENDING_INVESTIGATION")
    void autoResolveShortAmountIsPendingInvestigation() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.autoResolve(discrepancy);

        assertEquals(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION,
                result.getResolutionType());
    }

    @Test
    @DisplayName("autoResolve — 已解决的差错不重复处理")
    void autoResolveAlreadyResolvedDoesNotChange() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
        discrepancy.setAmountDiff(new BigDecimal("0.005"));

        ReconciliationDiscrepancy result = resolutionService.autoResolve(discrepancy);

        // 不调用 save，直接返回原对象
        verify(discrepancyRepository, never()).save(any());
        assertSame(discrepancy, result);
    }

    // ==================== startInvestigation ====================

    @Test
    @DisplayName("startInvestigation — DISCOVERED → INVESTIGATING")
    void startInvestigationFromDiscovered() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));
        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.startInvestigation(1L);

        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING, result.getStatus());
    }

    @Test
    @DisplayName("startInvestigation — 非 DISCOVERED 状态不允许流转")
    void startInvestigationFromNonDiscoveredThrows() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));

        assertThrows(IllegalStateException.class, () -> resolutionService.startInvestigation(1L));
    }

    @Test
    @DisplayName("startInvestigation — 不存在的 ID 抛出异常")
    void startInvestigationNotFoundThrows() {
        when(discrepancyRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> resolutionService.startInvestigation(999L));
    }

    // ==================== resolveDiscrepancy ====================

    @Test
    @DisplayName("resolveDiscrepancy — INVESTIGATING → RESOLVED")
    void resolveDiscrepancyFromInvestigating() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));
        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.resolveDiscrepancy(1L, "Verified and corrected");

        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, result.getStatus());
        assertEquals("Verified and corrected", result.getResolutionNote());
        assertNotNull(result.getResolvedAt());
    }

    @Test
    @DisplayName("resolveDiscrepancy — DISCOVERED → RESOLVED（允许跳过 INVESTIGATING）")
    void resolveDiscrepancyFromDiscovered() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));
        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.resolveDiscrepancy(1L, "Direct resolve");

        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, result.getStatus());
    }

    @Test
    @DisplayName("resolveDiscrepancy — RESOLVED 状态不允许再次解决")
    void resolveDiscrepancyFromResolvedThrows() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));

        assertThrows(IllegalStateException.class,
                () -> resolutionService.resolveDiscrepancy(1L, "test"));
    }

    // ==================== escalateDiscrepancy ====================

    @Test
    @DisplayName("escalateDiscrepancy — INVESTIGATING → ESCALATED")
    void escalateDiscrepancyFromInvestigating() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));
        when(discrepancyRepository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy result = resolutionService.escalateDiscrepancy(1L, "Needs senior review");

        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.ESCALATED, result.getStatus());
        assertEquals("Needs senior review", result.getResolutionNote());
    }

    @Test
    @DisplayName("escalateDiscrepancy — ESCALATED 状态不允许再次升级")
    void escalateDiscrepancyFromEscalatedThrows() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH,
                ReconciliationDiscrepancy.DiscrepancyStatus.ESCALATED);
        discrepancy.setId(1L);

        when(discrepancyRepository.findById(1L)).thenReturn(Optional.of(discrepancy));

        assertThrows(IllegalStateException.class,
                () -> resolutionService.escalateDiscrepancy(1L, "test"));
    }

    // ==================== saveDiscrepancies ====================

    @Test
    @DisplayName("saveDiscrepancies — 批量保存差错记录")
    void saveDiscrepanciesBatchSave() {
        ReconciliationDiscrepancy d1 = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        ReconciliationDiscrepancy d2 = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        List<ReconciliationDiscrepancy> saved = resolutionService.saveDiscrepancies(List.of(d1, d2));

        assertEquals(2, saved.size());
        verify(discrepancyRepository).saveAll(any());
    }

    @Test
    @DisplayName("saveDiscrepancies — 空列表不调用 saveAll")
    void saveDiscrepanciesEmptyList() {
        List<ReconciliationDiscrepancy> saved = resolutionService.saveDiscrepancies(List.of());

        assertTrue(saved.isEmpty());
        verify(discrepancyRepository, never()).saveAll(any());
    }

    // ==================== 查询方法 ====================

    @Test
    @DisplayName("getDiscrepanciesByMerchant — 返回商户的所有差错")
    void getDiscrepanciesByMerchant() {
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(d));

        List<ReconciliationDiscrepancy> result = resolutionService.getDiscrepanciesByMerchant(MERCHANT_ID);

        assertEquals(1, result.size());
        verify(discrepancyRepository).findByMerchantId(MERCHANT_ID);
    }

    @Test
    @DisplayName("getDiscrepanciesByMerchantAndStatus — 按状态筛选差错")
    void getDiscrepanciesByMerchantAndStatus() {
        ReconciliationDiscrepancy d = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        when(discrepancyRepository.findByMerchantIdAndStatus(MERCHANT_ID,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED))
                .thenReturn(List.of(d));

        List<ReconciliationDiscrepancy> result =
                resolutionService.getDiscrepanciesByMerchantAndStatus(
                        MERCHANT_ID, ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);

        assertEquals(1, result.size());
    }

    // ==================== 辅助方法 ====================

    private ReconciliationDiscrepancy createDiscrepancy(
            ReconciliationDiscrepancy.DiscrepancyType type,
            ReconciliationDiscrepancy.DiscrepancyStatus status) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setMerchantId(MERCHANT_ID);
        discrepancy.setDiscrepancyType(type);
        discrepancy.setStatus(status);
        discrepancy.setTransactionId("ORD-001");
        discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW);
        discrepancy.setCreatedAt(LocalDateTime.now());
        discrepancy.setUpdatedAt(LocalDateTime.now());
        return discrepancy;
    }
}