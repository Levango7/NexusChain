package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.audit.AuditEventRepository;
import org.nexus.gateway.audit.AuditLogService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 差错渠道归属补录门禁（P1 修复 2026-10-11）。
 *
 * <p>补录是"人工数据修正"，直通资金路径（补对了才能走渠道退款），因此两条硬约束必须钉住：</p>
 * <ol>
 *   <li>**仅 null → 有值**：已有归属拒绝覆盖（改错渠道会让退款退错对象，比退不了更严重）；</li>
 *   <li>取值只接受 WECHAT / ALIPAY（与 ChannelRefundService 支持范围一致），不猜。</li>
 * </ol>
 */
class DiscrepancyChannelAnnotationTest {

    private ReconciliationDiscrepancyRepository repository;
    private DiscrepancyResolutionService service;
    private AuditLogService auditLogService;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        repository = mock(ReconciliationDiscrepancyRepository.class);
        auditLogService = new AuditLogService(mock(AuditEventRepository.class));
        service = new DiscrepancyResolutionService(repository, null);
        service.setAuditLogService(auditLogService);
        when(repository.save(any(ReconciliationDiscrepancy.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private ReconciliationDiscrepancy discrepancy(String channelType) {
        ReconciliationDiscrepancy d = new ReconciliationDiscrepancy();
        d.setId(42L);
        d.setMerchantId(MERCHANT_ID);
        d.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT);
        d.setTransactionId("TXN-1");
        d.setChannelType(channelType);
        d.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        when(repository.findById(42L)).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    @DisplayName("补录成功：null → WECHAT（小写也接受，统一大写落库）")
    void annotate_nullToWechat() {
        ReconciliationDiscrepancy d = discrepancy(null);

        ReconciliationDiscrepancy saved = service.annotateChannelType(42L, "wechat");

        assertEquals("WECHAT", saved.getChannelType());
        assertEquals("WECHAT", d.getChannelType());
        verify(repository).save(d);
    }

    @Test
    @DisplayName("硬约束①：已有归属 → 拒绝覆盖（防改错渠道导致退错对象）")
    void annotate_existingAttribution_refused() {
        discrepancy("WECHAT");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.annotateChannelType(42L, "ALIPAY"));

        assertTrue(ex.getMessage().contains("拒绝覆盖"), ex.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("硬约束②：不支持的渠道值 → 拒绝（不猜）")
    void annotate_unsupportedChannel_refused() {
        discrepancy(null);

        assertThrows(IllegalStateException.class, () -> service.annotateChannelType(42L, "STRIPE"));
        assertThrows(IllegalStateException.class, () -> service.annotateChannelType(42L, null));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("差错不存在 → IllegalArgumentException（控制器映射 404）")
    void annotate_missingDiscrepancy() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.annotateChannelType(99L, "WECHAT"));
    }

    @Test
    @DisplayName("工作队列：列出待补归属的差错（运营待办清单）")
    void workQueue_listsUnattributed() {
        // 注意：helper 内部会 stub findById —— 必须先造对象再 stub，否则 Mockito 报
        // UnfinishedStubbing（嵌套 stub）
        ReconciliationDiscrepancy legacy = new ReconciliationDiscrepancy();
        legacy.setId(7L);
        legacy.setMerchantId(MERCHANT_ID);
        legacy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT);
        when(repository.findByChannelTypeIsNull()).thenReturn(List.of(legacy));

        List<ReconciliationDiscrepancy> queue = service.findWithoutChannelAttribution();

        assertEquals(1, queue.size());
        assertNull(queue.get(0).getChannelType());
    }
}
