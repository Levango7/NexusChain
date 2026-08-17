package org.nexus.gateway.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 三阶段执行与对账配置（P2-F3）。
 *
 * <p>绑定 {@code nexus.gatewayservice.execution.*} 前缀的配置项，
 * 控制定时对账任务的超时阈值与调度周期。</p>
 *
 * <ul>
 *   <li>{@code pending-timeout-minutes}：PENDING 状态超时阈值（分钟），
 *       超过此值仍为 PENDING 的记录由 {@link CompensationService} 处理，默认 30</li>
 *   <li>{@code reconciliation-cron}：对账任务 cron 表达式，默认每 5 分钟</li>
 *   <li>{@code reconciliation-batch-size}：单次对账处理的记录数上限，
 *       避免大批量对账占用过多数据库连接，默认 100</li>
 *   <li>{@code enabled}：是否启用定时对账，默认 true（测试环境可关闭）</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "nexus.gatewayservice.execution")
public class ExecutionConfig {

    /** PENDING 状态超时阈值（分钟） */
    private int pendingTimeoutMinutes = 30;

    /** 对账任务 cron 表达式（Spring 6 六字段格式：秒 分 时 日 月 周），默认每 5 分钟 */
    private String reconciliationCron = "0 */5 * * * *";

    /** 单次对账处理的记录数上限 */
    private int reconciliationBatchSize = 100;

    /** 是否启用定时对账 */
    private boolean enabled = true;

    public int getPendingTimeoutMinutes() { return pendingTimeoutMinutes; }
    public void setPendingTimeoutMinutes(int pendingTimeoutMinutes) {
        this.pendingTimeoutMinutes = pendingTimeoutMinutes;
    }

    public String getReconciliationCron() { return reconciliationCron; }
    public void setReconciliationCron(String reconciliationCron) {
        this.reconciliationCron = reconciliationCron;
    }

    public int getReconciliationBatchSize() { return reconciliationBatchSize; }
    public void setReconciliationBatchSize(int reconciliationBatchSize) {
        this.reconciliationBatchSize = reconciliationBatchSize;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}