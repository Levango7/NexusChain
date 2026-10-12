package org.nexus.settlement.funds;

import org.nexus.settlement.clearing.Ledger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 热→冷归集的多签审批闸门（P0 修复 2026-10-11）。
 *
 * <p>修复前：{@code transferToCold()} 带 TODO 并**直接转账**——热钱包资金可被无审批转出。
 * 现装配审批服务后，无审批面直接拒绝；审批版要求 ① 状态 APPROVED ② 用途为
 * {@code COLD_TRANSFER}（防挪用无关审批）。</p>
 */
class FundSweepMultiSigGateTest {

    private Ledger ledger;
    private MultiSigApprovalService approvalService;
    private DefaultFundSweepService service;

    @BeforeEach
    void setUp() {
        ledger = mock(Ledger.class);
        approvalService = mock(MultiSigApprovalService.class);
        service = new DefaultFundSweepService(ledger, new BigDecimal("0"), null, approvalService);
        // 热钱包余额 > 阈值（阈值 0）
        when(ledger.balanceOf(DefaultFundSweepService.HOT_WALLET)).thenReturn(new BigDecimal("500"));
    }

    private void stubApproval(String approvalId, boolean approved, String orderId) {
        when(approvalService.isApproved(approvalId)).thenReturn(approved);
        if (approved) {
            MultiSigApprovalService.ApprovalRequest req = mock(MultiSigApprovalService.ApprovalRequest.class);
            when(req.getOrderId()).thenReturn(orderId);
            when(approvalService.getStatus(approvalId)).thenReturn(req);
        }
    }

    @Test
    @DisplayName("闸门①：装配审批服务后，无审批面 transferToCold() 直接拒绝（不再无审批转出）")
    void noArgPath_refusedWhenApprovalWired() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.transferToCold());
        assertTrue(ex.getMessage().contains("多签审批"), ex.getMessage());
        verify(ledger, never()).bookTransfer(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("闸门②：未通过审批 → 拒绝")
    void notApproved_refused() {
        stubApproval("AP-1", false, null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.transferToCold("AP-1"));
        assertTrue(ex.getMessage().contains("审批未通过"), ex.getMessage());
        verify(ledger, never()).bookTransfer(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("闸门③：审批用途不符（挪用无关审批）→ 拒绝")
    void wrongScope_refused() {
        stubApproval("AP-2", true, "SOME_OTHER_PURPOSE");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.transferToCold("AP-2"));
        assertTrue(ex.getMessage().contains("用途不符"), ex.getMessage());
        verify(ledger, never()).bookTransfer(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("正路：已批准且用途为 COLD_TRANSFER → 执行转账")
    void approved_coldTransferScope_executes() {
        stubApproval("AP-3", true, DefaultFundSweepService.COLD_TRANSFER_APPROVAL_SCOPE);

        int result = service.transferToCold("AP-3");

        assertEquals(1, result);
        verify(ledger).bookTransfer(eq(DefaultFundSweepService.HOT_WALLET),
                eq(DefaultFundSweepService.COLD_WALLET), eq(new BigDecimal("500")),
                eq("COLD_TRANSFER"));
    }

    @Test
    @DisplayName("兼容：未装配审批服务（旧装配/单测）→ 保持历史行为")
    void legacyAssembly_keepsBehavior() {
        DefaultFundSweepService legacy = new DefaultFundSweepService(
                ledger, new BigDecimal("0"), null, null);
        assertEquals(1, legacy.transferToCold(), "无审批服务时保持历史行为（测试路径）");
    }
}
