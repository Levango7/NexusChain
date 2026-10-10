package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.reconciliation.link.DiscrepancyResolvedEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DiscrepancyResolvedEvent 发布回归测试（P0 修复 2026-10-09）。
 *
 * <p>背景：{@code DiscrepancyResolvedEvent} 此前只有监听器没有发布方，
 * "差错解决 → 挂账自动核销"联动是死代码。本测试钉住两个发布点：
 * {@code resolveDiscrepancy}（人工解决）与 {@code autoResolve}（容差内自动解决）。</p>
 */
class DiscrepancyResolvedEventPublishTest {

    private ReconciliationDiscrepancyRepository discrepancyRepository;
    private ApplicationEventPublisher eventPublisher;
    private DiscrepancyResolutionService resolutionService;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        discrepancyRepository = mock(ReconciliationDiscrepancyRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        resolutionService = new DiscrepancyResolutionService(discrepancyRepository, eventPublisher);
        resolutionService.setAmountTolerance(new BigDecimal("0.01"));
    }

    private ReconciliationDiscrepancy discovered(ReconciliationDiscrepancy.DiscrepancyType type) {
        ReconciliationDiscrepancy d = new ReconciliationDiscrepancy();
        d.setId(9001L);
        d.setMerchantId(MERCHANT_ID);
        d.setDiscrepancyType(type);
        d.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        return d;
    }

    @Test
    @DisplayName("resolveDiscrepancy（人工解决）→ 发布 DiscrepancyResolvedEvent")
    void resolveDiscrepancy_publishesEvent() {
        ReconciliationDiscrepancy d = discovered(ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT);
        d.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.INVESTIGATING);
        when(discrepancyRepository.findById(9001L)).thenReturn(Optional.of(d));
        when(discrepancyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy saved = resolutionService.resolveDiscrepancy(9001L, "已人工核销");

        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, saved.getStatus());
        verify(eventPublisher).publishEvent(any(DiscrepancyResolvedEvent.class));
    }

    @Test
    @DisplayName("autoResolve（容差内自动解决）→ 发布 DiscrepancyResolvedEvent")
    void autoResolve_resolved_publishesEvent() {
        ReconciliationDiscrepancy d = discovered(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        d.setAmountDiff(new BigDecimal("0.005"));
        when(discrepancyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy saved = resolutionService.autoResolve(d);

        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, saved.getStatus());
        verify(eventPublisher).publishEvent(any(DiscrepancyResolvedEvent.class));
    }

    @Test
    @DisplayName("autoResolve（超容差 → 转人工）→ 不发布事件")
    void autoResolve_manualReview_noEvent() {
        ReconciliationDiscrepancy d = discovered(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        d.setAmountDiff(new BigDecimal("99.99"));
        when(discrepancyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReconciliationDiscrepancy saved = resolutionService.autoResolve(d);

        // 超容差 → 转人工复核，状态仍 DISCOVERED，不发布"已解决"事件
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, saved.getStatus());
        assertEquals(ReconciliationDiscrepancy.ResolutionType.MANUAL_REVIEW, saved.getResolutionType());
        verify(eventPublisher, never()).publishEvent(any(DiscrepancyResolvedEvent.class));
    }
}
