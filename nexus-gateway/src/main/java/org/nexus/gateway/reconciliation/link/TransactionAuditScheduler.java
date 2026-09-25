package org.nexus.gateway.reconciliation.link;

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
 * <p>需要确保 Spring 异步调度已启用（{@code @EnableScheduling}）。</p>
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