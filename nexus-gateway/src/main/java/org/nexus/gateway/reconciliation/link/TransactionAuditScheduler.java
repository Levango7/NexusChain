package org.nexus.gateway.reconciliation.link;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 交易对账审计调度器 — 每日 02:00 自动执行审计。
 *
 * <p>定时任务配置：{@code @Scheduled(cron = "0 0 2 * * ?")}，每天凌晨 2 点执行。
 * 审计范围：对前一天的所有交易执行正向 + 反向对账审计。</p>
 *
 * <p><b>为什么必须挂 ShedLock</b>：prod 是 {@code values-prod.yaml:20-21} 的
 * {@code nexus-gateway.replicaCount: 3}，三个副本会在同一刻各跑一遍全量审计；
 * 而 {@code transaction_audit_records} 的迁移（V62）只建了
 * {@code idx_tar_audit_date} 与 {@code idx_tar_audit_date_merchant} 两个<b>非唯一</b>索引，
 * 没有任何约束挡重复 ⇒ <b>同一天会出现多份重复审计报告</b>。</p>
 *
 * <p><b>严重性边界</b>：下钻 {@code TransactionAuditService#executeAudit} 可见它只读差错记录、
 * 只写审计报告行，<b>不触发任何资金动作</b>。所以这是<b>报告完整性</b>问题，不是资金事故；
 * 上锁是与之最相称的最小改法（同仓先例：{@code DailyReconciliationScheduler#runDailyReconciliation}
 * 同为每日 cron 且已上锁）。</p>
 *
 * <p>需要确保 Spring 异步调度与 {@code @EnableSchedulerLock} 已启用
 * （后者已在 {@code GatewayApplication} 上）。</p>
 */
@Component
public class TransactionAuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransactionAuditScheduler.class);

    private final TransactionAuditService transactionAuditService;

    public TransactionAuditScheduler(TransactionAuditService transactionAuditService) {
        this.transactionAuditService = transactionAuditService;
    }

    /**
     * 每日 02:00 执行交易对账审计。
     *
     * <p>审计前一天的交易记录，生成正向和反向审计报告。
     * merchantId 为 null 表示全量审计（所有商户）。</p>
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @SchedulerLock(name = "transactionAuditDaily", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void dailyAudit() {
        LocalDate auditDate = LocalDate.now().minusDays(1);
        log.info("定时审计任务启动: auditDate={}", auditDate);

        try {
            var results = transactionAuditService.executeAudit(auditDate, null);
            log.info("定时审计任务完成: auditDate={}, records={}", auditDate, results.size());
        } catch (Exception e) {
            log.error("定时审计任务失败: auditDate={}, error={}", auditDate, e.getMessage(), e);
        }
    }
}