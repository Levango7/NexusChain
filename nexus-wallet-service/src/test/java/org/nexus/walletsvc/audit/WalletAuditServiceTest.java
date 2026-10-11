package org.nexus.walletsvc.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 钱包侧审计门禁（P0 补齐 2026-10-11）。
 *
 * <p>钉住两件事：① 四个提现节点能落库且字段正确；② <b>旁路铁律</b>——审计写失败
 * 绝不向上抛（否则提现流程会被审计故障卡死）。</p>
 */
class WalletAuditServiceTest {

    private WalletAuditEventRepository repository;
    private WalletAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(WalletAuditEventRepository.class);
        when(repository.save(any(WalletAuditEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new WalletAuditService(repository);
    }

    @Test
    @DisplayName("执行成功事件：带 requestId / 收款地址 / 金额 / 链上哈希")
    void recordsExecutedEvent() {
        service.record("WITHDRAWAL_EXECUTED", "WD-001", "0xTo",
                new java.math.BigDecimal("1.5"), "NEX", "txHash=0xabc");

        ArgumentCaptor<WalletAuditEvent> c = ArgumentCaptor.forClass(WalletAuditEvent.class);
        verify(repository).save(c.capture());
        WalletAuditEvent e = c.getValue();
        assertEquals("WITHDRAWAL_EXECUTED", e.getAction());
        assertEquals("WD-001", e.getRequestId());
        assertEquals("0xTo", e.getToAddress());
        assertEquals("1.5", e.getAmount());
        assertEquals("NEX", e.getCurrency());
        assertTrue(e.getDetail().contains("0xabc"));
        assertNotNull(e.getEventTime());
    }

    @Test
    @DisplayName("失败事件：带失败原因（资金操作失败同样留痕）")
    void recordsFailedEvent() {
        service.record("WITHDRAWAL_FAILED", "WD-002", "0xTo", "2", "NEX", "error=signer down");
        ArgumentCaptor<WalletAuditEvent> c = ArgumentCaptor.forClass(WalletAuditEvent.class);
        verify(repository).save(c.capture());
        assertEquals("WITHDRAWAL_FAILED", c.getValue().getAction());
        assertTrue(c.getValue().getDetail().contains("signer down"));
    }

    @Test
    @DisplayName("旁路铁律：落库失败不得抛异常（提现流程不被审计故障卡死）")
    void auditFailureNeverThrows() {
        when(repository.save(any(WalletAuditEvent.class))).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() ->
                service.record("WITHDRAWAL_REQUESTED", "WD-003", "0xTo", "1", "NEX", null));
    }

    @Test
    @DisplayName("空金额/空 detail 不炸（字段可空）")
    void nullFieldsTolerated() {
        assertDoesNotThrow(() -> service.record("WITHDRAWAL_REQUESTED", "WD-004", null, null, null, null));
        ArgumentCaptor<WalletAuditEvent> c = ArgumentCaptor.forClass(WalletAuditEvent.class);
        verify(repository).save(c.capture());
        assertNull(c.getValue().getAmount());
        assertNull(c.getValue().getToAddress());
    }
}
