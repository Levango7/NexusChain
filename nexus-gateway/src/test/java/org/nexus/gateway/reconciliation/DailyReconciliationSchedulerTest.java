package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.reconciliation.bill.BillDownloadProxy;
import org.nexus.gateway.reconciliation.bill.BillDownloadResult;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.repository.MerchantRepository;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DailyReconciliationScheduler} 单元测试 — P0 修复（2026-10-09）回归门禁。
 *
 * <p>背景：{@code runDailyReconciliation} 此前只有单测调用方，没有任何生产触发点。
 * 本调度器补上自动触发；测试覆盖：正常闭环、空对账单 fail-closed 跳过、
 * 单商户异常不阻断其余商户。</p>
 */
class DailyReconciliationSchedulerTest {

    private BillDownloadProxy billDownloadProxy;
    private ReconciliationFileService reconciliationFileService;
    private MerchantRepository merchantRepository;
    private DailyReconciliationScheduler scheduler;

    private static final Long MERCHANT_A = 501L;
    private static final Long MERCHANT_B = 502L;

    @BeforeEach
    void setUp() throws Exception {
        billDownloadProxy = mock(BillDownloadProxy.class);
        reconciliationFileService = mock(ReconciliationFileService.class);
        merchantRepository = mock(MerchantRepository.class);
        scheduler = new DailyReconciliationScheduler(
                billDownloadProxy, reconciliationFileService, merchantRepository);
        setField(scheduler, "enabled", true);
        setField(scheduler, "channelsCsv", "WECHAT,ALIPAY");
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Merchant merchant(Long id) {
        Merchant m = new Merchant();
        m.setId(id);
        return m;
    }

    private BillDownloadResult successDownload(String channel, Long merchantId, String csv) {
        return BillDownloadResult.success(channel, merchantId, LocalDate.now().minusDays(1).toString(),
                csv, List.of(), false);
    }

    private ReconciliationDiffReport emptyReport() {
        ReconciliationDiffReport r = new ReconciliationDiffReport();
        r.setMatchedCount(0);
        r.setDiscrepancies(List.of());
        return r;
    }

    @Test
    @DisplayName("正常闭环：每商户×每渠道下载成功 → 调用 runDailyReconciliation")
    void runDaily_success_callsReconciliationPerMerchantAndChannel() throws Exception {
        when(merchantRepository.findAll()).thenReturn(List.of(merchant(MERCHANT_A)));
        when(billDownloadProxy.downloadBill(eq("WECHAT"), eq(MERCHANT_A), anyString()))
                .thenReturn(successDownload("WECHAT", MERCHANT_A, "csv-wechat"));
        when(billDownloadProxy.downloadBill(eq("ALIPAY"), eq(MERCHANT_A), anyString()))
                .thenReturn(successDownload("ALIPAY", MERCHANT_A, "csv-alipay"));
        when(reconciliationFileService.runDailyReconciliation(eq(MERCHANT_A), anyString(), any()))
                .thenReturn(emptyReport());

        scheduler.runDailyReconciliation();

        verify(reconciliationFileService).runDailyReconciliation(
                eq(MERCHANT_A), eq("csv-wechat"), eq(LocalDate.now().minusDays(1)));
        verify(reconciliationFileService).runDailyReconciliation(
                eq(MERCHANT_A), eq("csv-alipay"), eq(LocalDate.now().minusDays(1)));
    }

    @Test
    @DisplayName("fail-closed：对账单为空/下载失败 → 跳过对账（不用空账单误判单边长款）")
    void runDaily_emptyBill_skipsReconciliation() throws Exception {
        when(merchantRepository.findAll()).thenReturn(List.of(merchant(MERCHANT_A)));
        when(billDownloadProxy.downloadBill(eq("WECHAT"), eq(MERCHANT_A), anyString()))
                .thenReturn(BillDownloadResult.failure("WECHAT", MERCHANT_A, "2026-10-08", "下载失败"));
        when(billDownloadProxy.downloadBill(eq("ALIPAY"), eq(MERCHANT_A), anyString()))
                .thenReturn(successDownload("ALIPAY", MERCHANT_A, "   "));

        assertDoesNotThrow(() -> scheduler.runDailyReconciliation());

        verify(reconciliationFileService, never()).runDailyReconciliation(any(), anyString(), any());
    }

    @Test
    @DisplayName("隔离性：单商户对账异常不阻断其余商户")
    void runDaily_singleMerchantFailure_doesNotBlockOthers() throws Exception {
        setField(scheduler, "channelsCsv", "WECHAT");
        when(merchantRepository.findAll())
                .thenReturn(List.of(merchant(MERCHANT_A), merchant(MERCHANT_B)));
        when(billDownloadProxy.downloadBill(anyString(), anyLong(), anyString()))
                .thenReturn(successDownload("WECHAT", MERCHANT_A, "csv"));
        when(reconciliationFileService.runDailyReconciliation(eq(MERCHANT_A), anyString(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(reconciliationFileService.runDailyReconciliation(eq(MERCHANT_B), anyString(), any()))
                .thenReturn(emptyReport());

        assertDoesNotThrow(() -> scheduler.runDailyReconciliation());

        // 商户 B 仍被处理（A 的异常被隔离）
        verify(reconciliationFileService).runDailyReconciliation(eq(MERCHANT_B), anyString(), any());
    }

    @Test
    @DisplayName("开关关闭：enabled=false → 不做任何下载与对账")
    void runDaily_disabled_noop() throws Exception {
        setField(scheduler, "enabled", false);

        scheduler.runDailyReconciliation();

        verifyNoInteractions(billDownloadProxy);
        verifyNoInteractions(reconciliationFileService);
    }
}
