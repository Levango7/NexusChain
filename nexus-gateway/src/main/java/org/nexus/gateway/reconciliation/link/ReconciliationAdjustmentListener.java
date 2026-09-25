package org.nexus.gateway.reconciliation.link;

import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.nexus.gateway.reconciliation.SuspenseAccount;
import org.nexus.gateway.reconciliation.SuspenseAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 对账差异调整事件监听器。
 *
 * <p>监听两类事件并异步处理：
 * <ul>
 *   <li>{@link ReconciliationDiffReportEvent} — 对账完成后处理差异报告，自动调整容差内差异</li>
 *   <li>{@link DiscrepancyResolvedEvent} — 差错解决后联动处理（核销挂账、调整资金）</li>
 * </ul>
 * </p>
 *
 * <p>使用 {@code @Async} 异步处理，避免阻塞对账主流程。
 * 使用 {@code @EventListener} 监听 Spring 应用事件。</p>
 */
@Component
public class ReconciliationAdjustmentListener {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationAdjustmentListener.class);

    private final ReconciliationAdjustmentService adjustmentService;
    private final SuspenseAccountService suspenseAccountService;
    private final SuspenseWriteoffService suspenseWriteoffService;

    public ReconciliationAdjustmentListener(
            ReconciliationAdjustmentService adjustmentService,
            SuspenseAccountService suspenseAccountService,
            SuspenseWriteoffService suspenseWriteoffService) {
        this.adjustmentService = adjustmentService;
        this.suspenseAccountService = suspenseAccountService;
        this.suspenseWriteoffService = suspenseWriteoffService;
    }

    /**
     * 监听对账差异报告事件 — 异步处理差异调整。
     *
     * <p>对账完成后自动触发：
     * <ol>
     *   <li>调用 {@link ReconciliationAdjustmentService#processDiffReport} 处理差异</li>
     *   <li>容差内差异自动调整资金，超出容差创建待审批记录</li>
     * </ol>
     * </p>
     *
     * @param event 对账差异报告事件
     */
    @EventListener
    @Async
    public void handleDiffReportEvent(ReconciliationDiffReportEvent event) {
        log.info("收到对账差异报告事件: merchantId={}, reconciliationFileId={}",
                event.getMerchantId(), event.getReconciliationFileId());

        try {
            adjustmentService.processDiffReport(
                    event.getMerchantId(),
                    event.getDiffReport(),
                    event.getReconciliationFileId());
            log.info("对账差异报告处理完成: merchantId={}", event.getMerchantId());
        } catch (Exception e) {
            log.error("对账差异报告处理失败: merchantId={}, error={}",
                    event.getMerchantId(), e.getMessage(), e);
        }
    }

    /**
     * 监听差错已解决事件 — 异步联动处理。
     *
     * <p>差错解决后联动处理：
     * <ol>
     *   <li>查找关联的挂账记录</li>
     *   <li>对挂账记录执行自动核销（金额在阈值内）</li>
     * </ol>
     * </p>
     *
     * @param event 差错已解决事件
     */
    @EventListener
    @Async
    public void handleDiscrepancyResolvedEvent(DiscrepancyResolvedEvent event) {
        ReconciliationDiscrepancy discrepancy = event.getDiscrepancy();
        log.info("收到差错已解决事件: discrepancyId={}, merchantId={}, type={}",
                discrepancy.getId(), discrepancy.getMerchantId(), discrepancy.getDiscrepancyType());

        try {
            // 查找关联的挂账记录
            var suspenseAccounts = suspenseAccountService.findByDiscrepancyId(discrepancy.getId());

            for (SuspenseAccount suspenseAccount : suspenseAccounts) {
                if (suspenseAccount.getStatus() == SuspenseAccount.SuspenseStatus.PENDING) {
                    // 尝试自动核销
                    suspenseWriteoffService.autoWriteoff(suspenseAccount.getId());
                    log.info("挂账自动核销完成: suspenseAccountId={}, discrepancyId={}",
                            suspenseAccount.getId(), discrepancy.getId());
                }
            }
        } catch (Exception e) {
            log.error("差错联动处理失败: discrepancyId={}, error={}",
                    discrepancy.getId(), e.getMessage(), e);
        }
    }
}