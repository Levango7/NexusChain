package org.nexus.gateway.settlement;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.reconciliation.ReconciliationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 对账调度器（Settlement ↔ Reconciliation 定时对账入口）。
 *
 * <p>定时调用 {@link ClearingEngine#reconcile} 触发链上对账，
 * 输出结构化报告（source/双边总量/差错金额汇总/details），供监控告警采集。</p>
 *
 * <p>与 {@link SettlementScheduler} 同模式：
 * <ul>
 *   <li>{@code @Scheduled} cron 可配置（默认每 30 分钟），与结算调度错峰
 *       （结算每 3 分钟、对账每 30 分钟，对账总是能看到已回填的链上凭证）</li>
 *   <li>ShedLock {@code @SchedulerLock} 保证多实例部署时同一时刻仅一个实例执行对账</li>
 *   <li>对账失败不外抛（调度线程安全），差错由报告维度承载、人工介入处理</li>
 * </ul></p>
 */
@Component
public class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ClearingEngine clearingEngine;
    private final boolean reconciliationEnabled;

    public ReconciliationScheduler(ClearingEngine clearingEngine,
                                   @Value("${nexus.settlement.reconciliation-enabled:true}") boolean reconciliationEnabled) {
        this.clearingEngine = clearingEngine;
        this.reconciliationEnabled = reconciliationEnabled;
    }

    /**
     * 定时对账主入口：默认每 30 分钟执行（cron 可配置）。
     *
     * <p>调用 ClearingEngine.reconcile 触发链上对账并上报差错。
     * 差错数大于 0 时以 WARN 级别输出结构化摘要，供日志告警系统采集。</p>
     */
    @Scheduled(cron = "${nexus.settlement.reconciliation-cron:0 */30 * * * *}")
    @SchedulerLock(name = "reconciliationScheduler", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void reconcile() {
        if (!reconciliationEnabled) {
            log.debug("Settlement reconciliation disabled, skip");
            return;
        }

        try {
            ReconciliationReport report = clearingEngine.reconcile(null);
            if (report == null) {
                log.debug("Reconciliation returned no report, skip");
                return;
            }
            if (report.getDiscrepancyCount() > 0) {
                log.warn("Reconciliation found discrepancies: source={}, date={}, matched={}, discrepancies={}, "
                                + "totalLocal={}, totalExternal={}, totalDiscrepancyAmount={}",
                        report.getSource(), report.getReconcileDate(), report.getMatchedCount(),
                        report.getDiscrepancyCount(), report.getTotalLocal(), report.getTotalExternal(),
                        report.getTotalDiscrepancyAmount());
                if (report.getDetails() != null) {
                    report.getDetails().forEach(d -> log.warn("Reconciliation detail: {}", d));
                }
            } else {
                log.info("Reconciliation clean: source={}, date={}, matched={}",
                        report.getSource(), report.getReconcileDate(), report.getMatchedCount());
            }
        } catch (RuntimeException e) {
            log.error("Reconciliation failed: {}", e.getMessage(), e);
        }
    }
}
