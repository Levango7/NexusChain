package org.nexus.gateway.reconciliation.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancyRepository;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PR #83（P1-3，2026-10-12）：自动调账动账后必须把关联差错回写为 RESOLVED。
 *
 * <p>缺陷形态：{@code executeAdjustment} 真动账（deposit/withdrawWithType）却从不写
 * 差错状态，差错永停 DISCOVERED —— 挂账核销链不触发、运营台账永远"未处理"、
 * 对账差异率被永久污染。这里锁的是回写语义本身，而不是"编译得过"。</p>
 *
 * <p>用手工 mock 而非 {@code @Mock + MockitoExtension}：本类只关心这几个协作对象，
 * 且部分用例故意不 stub（严格桩会因"未使用"报错而诱导人去塞空断言）。</p>
 */
class ReconciliationAdjustmentDiscrepancyWritebackTest {

    private final ReconciliationAdjustmentRepository adjustmentRepository =
            mock(ReconciliationAdjustmentRepository.class);
    private final AccountService accountService = mock(AccountService.class);
    private final ReconciliationDiscrepancyRepository discrepancyRepository =
            mock(ReconciliationDiscrepancyRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private ReconciliationAdjustmentService serviceWithRepo() {
        ReconciliationAdjustmentService s = new ReconciliationAdjustmentService(
                adjustmentRepository, accountService, eventPublisher);
        s.setAutoApproveThreshold(new BigDecimal("1000"));
        s.setDiscrepancyRepository(discrepancyRepository);
        return s;
    }

    private ReconciliationAdjustment adjustment(Long discrepancyId, AdjustmentType type) {
        ReconciliationAdjustment a = new ReconciliationAdjustment();
        a.setId(7L);
        a.setMerchantId(100L);
        a.setDiscrepancyId(discrepancyId);
        a.setAdjustmentType(type);
        a.setAmount(new BigDecimal("50.00"));
        return a;
    }

    private ReconciliationDiscrepancy discrepancy(Long id,
            ReconciliationDiscrepancy.DiscrepancyStatus status, String note) {
        ReconciliationDiscrepancy d = new ReconciliationDiscrepancy();
        d.setId(id);
        d.setStatus(status);
        d.setResolutionNote(note);
        return d;
    }

    @Test
    @DisplayName("动账成功后：DISCOVERED 差错被置 RESOLVED，带 resolvedAt 与可追溯备注")
    void creditAdjustMarksDiscrepancyResolved() {
        ReconciliationDiscrepancy d = discrepancy(9L,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, null);
        when(discrepancyRepository.findById(9L)).thenReturn(Optional.of(d));

        serviceWithRepo().executeAdjustment(adjustment(9L, AdjustmentType.CREDIT_ADJUST));

        verify(accountService).depositWithType(eq(100L), any(BigDecimal.class),
                anyString(), eq(AccountOperationType.RECON_ADJUST));
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, d.getStatus());
        assertNotNull(d.getResolvedAt(), "RESOLVED 必须带时间戳，否则台账无法审计处理时点");
        assertNotNull(d.getResolutionNote());
        assertTrue(d.getResolutionNote().contains("CREDIT_ADJUST"),
                "备注要能看出是哪条路径写的: " + d.getResolutionNote());
        assertTrue(d.getResolutionNote().contains("50"),
                "备注要带金额: " + d.getResolutionNote());
        verify(discrepancyRepository).save(d);
    }

    @Test
    @DisplayName("扣账方向同样回写（DEBIT_ADJUST 不走 deposit 分支）")
    void debitAdjustMarksDiscrepancyResolved() {
        ReconciliationDiscrepancy d = discrepancy(11L,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, null);
        when(discrepancyRepository.findById(11L)).thenReturn(Optional.of(d));

        serviceWithRepo().executeAdjustment(adjustment(11L, AdjustmentType.DEBIT_ADJUST));

        verify(accountService).withdrawWithType(eq(100L), any(BigDecimal.class),
                anyString(), eq(AccountOperationType.RECON_ADJUST));
        verify(accountService, never()).depositWithType(anyLong(), any(), anyString(), any());
        assertEquals(ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, d.getStatus());
    }

    @Test
    @DisplayName("已是 RESOLVED 的差错不重复写 —— 保住人工填写的 resolutionNote")
    void alreadyResolvedIsNotOverwritten() {
        ReconciliationDiscrepancy d = discrepancy(12L,
                ReconciliationDiscrepancy.DiscrepancyStatus.RESOLVED, "人工核销：已与渠道确认");
        when(discrepancyRepository.findById(12L)).thenReturn(Optional.of(d));

        serviceWithRepo().executeAdjustment(adjustment(12L, AdjustmentType.CREDIT_ADJUST));

        assertEquals("人工核销：已与渠道确认", d.getResolutionNote(), "人工备注不得被自动路径覆盖");
        verify(discrepancyRepository, never()).save(any());
    }

    @Test
    @DisplayName("差错记录已不存在：不抛异常，资金调整照常完成")
    void missingDiscrepancyDoesNotBreakAdjustment() {
        when(discrepancyRepository.findById(13L)).thenReturn(Optional.empty());

        serviceWithRepo().executeAdjustment(adjustment(13L, AdjustmentType.CREDIT_ADJUST));

        verify(accountService).depositWithType(eq(100L), any(BigDecimal.class),
                anyString(), eq(AccountOperationType.RECON_ADJUST));
        verify(discrepancyRepository, never()).save(any());
    }

    @Test
    @DisplayName("discrepancyId 为空（历史数据）：跳过回写但不动账语义不变")
    void nullDiscrepancyIdSkipsWriteback() {
        serviceWithRepo().executeAdjustment(adjustment(null, AdjustmentType.CREDIT_ADJUST));

        verify(discrepancyRepository, never()).findById(anyLong());
        verify(discrepancyRepository, never()).save(any());
        verify(accountService).depositWithType(eq(100L), any(BigDecimal.class),
                anyString(), eq(AccountOperationType.RECON_ADJUST));
    }

    @Test
    @DisplayName("未注入差错仓库时保持旧行为（既有构造器/单测不被打破）")
    void withoutRepositoryInjectionBehavesAsBefore() {
        ReconciliationAdjustmentService s = new ReconciliationAdjustmentService(
                adjustmentRepository, accountService, eventPublisher);
        s.setAutoApproveThreshold(new BigDecimal("1000"));

        s.executeAdjustment(adjustment(14L, AdjustmentType.CREDIT_ADJUST));

        verify(accountService).depositWithType(eq(100L), any(BigDecimal.class),
                anyString(), eq(AccountOperationType.RECON_ADJUST));
        verifyNoInteractions(discrepancyRepository);
    }

    @Test
    @DisplayName("回写失败必须冒泡（同事务回滚），绝不能被吞成「钱动了、差错仍 DISCOVERED」")
    void writebackFailurePropagates() {
        ReconciliationDiscrepancy d = discrepancy(15L,
                ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED, null);
        when(discrepancyRepository.findById(15L)).thenReturn(Optional.of(d));
        when(discrepancyRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> serviceWithRepo()
                .executeAdjustment(adjustment(15L, AdjustmentType.CREDIT_ADJUST)));

        // 资金调用确实发生过 —— 回滚靠的是冒泡到 @Transactional，而不是就地忽略
        verify(accountService).depositWithType(eq(100L), any(BigDecimal.class),
                anyString(), eq(AccountOperationType.RECON_ADJUST));
    }

    @Test
    @DisplayName("已 executed 的调整记录幂等跳过：不动账也不回写")
    void alreadyExecutedIsIdempotentSkip() {
        ReconciliationAdjustment a = adjustment(16L, AdjustmentType.CREDIT_ADJUST);
        a.setExecuted(Boolean.TRUE);

        serviceWithRepo().executeAdjustment(a);

        verifyNoInteractions(accountService);
        verify(discrepancyRepository, never()).findById(anyLong());
    }
}
