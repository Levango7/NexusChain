package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 资金主路径审计门禁（P0 修复 2026-10-11）。
 *
 * <p>审计从"只有支付确认"扩到**退款 / 调账 / 出款 / 渠道退款**四条资金主路径。
 * 除"事件写没写"外，本测试额外钉住一条更重要的属性：<b>审计是旁路——审计写失败
 * 绝不阻断资金流程</b>（否则一个审计故障就会让退款/出款全线卡死）。</p>
 */
class MoneyPathAuditTest {

    private AuditEventRepository repository;
    private AuditLogService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        when(repository.save(any(AuditEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new AuditLogService(repository);
    }

    @Test
    @DisplayName("退款：recordRefund → 事件带 order 资源与动作")
    void recordRefund_writesEvent() {
        service.recordRefund("ORD-001", "REFUND_EXECUTED", "txHash=0xabc");
        ArgumentCaptor<AuditEventEntity> c = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(c.capture());
        assertEquals("REFUND_EXECUTED", c.getValue().getAction());
        assertEquals("order:ORD-001", c.getValue().getResource());
    }

    @Test
    @DisplayName("调账：recordAdjust → 事件带 reference 与金额（可与账本流水交叉核对）")
    void recordAdjust_writesEvent() {
        service.recordAdjust(500L, "RECON_ADJ_7", "RECON_ADJUST_DEBIT", "12.34");
        ArgumentCaptor<AuditEventEntity> c = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(c.capture());
        assertEquals("RECON_ADJUST_DEBIT", c.getValue().getAction());
        assertEquals("adjustment:RECON_ADJ_7", c.getValue().getResource());
        assertEquals(500L, c.getValue().getMerchantId());
        assertTrue(c.getValue().getDetail().contains("12.34"));
    }

    @Test
    @DisplayName("出款：recordPayout → 含 AUTO_WITHDRAW_NO_PAYOUT_ACCOUNT（fail-closed 也要留痕）")
    void recordPayout_writesEvent() {
        service.recordPayout(900L, "AUTO_WITHDRAW_NO_PAYOUT_ACCOUNT", "amount=100.00");
        ArgumentCaptor<AuditEventEntity> c = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(c.capture());
        assertEquals("AUTO_WITHDRAW_NO_PAYOUT_ACCOUNT", c.getValue().getAction());
    }

    @Test
    @DisplayName("渠道退款：recordChannelRefund → 带渠道与渠道交易号（资金出口可追查）")
    void recordChannelRefund_writesEvent() {
        service.recordChannelRefund(500L, "WECHAT", "WX_TX_1", "100.00", "5000000036");
        ArgumentCaptor<AuditEventEntity> c = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(c.capture());
        assertEquals("CHANNEL_REFUND_EXECUTED", c.getValue().getAction());
        assertEquals("channel:WECHAT:WX_TX_1", c.getValue().getResource());
        assertTrue(c.getValue().getDetail().contains("5000000036"));
    }

    @Test
    @DisplayName("旁路属性：落库失败不得阻断调用方（资金流程不被审计故障卡死）")
    void auditFailureNeverBreaksCaller() {
        when(repository.save(any(AuditEventEntity.class))).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service.recordRefund("ORD-1", "REFUND_APPROVED", "x"));
        assertDoesNotThrow(() -> service.recordAdjust(1L, "RECON_ADJ_1", "RECON_ADJUST_CREDIT", "1"));
        assertDoesNotThrow(() -> service.recordPayout(2L, "AUTO_WITHDRAW_EXECUTED", "x"));
        assertDoesNotThrow(() -> service.recordChannelRefund(3L, "WECHAT", "T", "1", "R"));
    }
}
