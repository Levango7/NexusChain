package org.nexus.gateway.reserve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 备付金监控调度器 — 定时检查备付金余额并触发预警和自动补充。
 *
 * <p>定时执行两个任务：</p>
 * <ol>
 *   <li>检查所有启用监控的商户备付金余额，设置预警级别</li>
 *   <li>对启用自动补充的商户，低于阈值时自动补充备付金</li>
 * </ol>
 *
 * <p>默认每 10 分钟执行一次，cron 表达式可配置。</p>
 */
@Component
public class ReserveMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReserveMonitorScheduler.class);

    private final ReserveFundService reserveFundService;
    private final boolean monitorEnabled;

    public ReserveMonitorScheduler(ReserveFundService reserveFundService,
                                    @Value("${nexus.reserve-monitor.enabled:true}") boolean monitorEnabled) {
        this.reserveFundService = reserveFundService;
        this.monitorEnabled = monitorEnabled;
    }

    /**
     * 定时备付金监控主入口：默认每 10 分钟执行（cron 可配置）。
     *
     * <p>先检查预警级别，再执行自动补充。</p>
     */
    @Scheduled(cron = "${nexus.reserve-monitor.cron:0 */10 * * * *}")
    public void monitor() {
        if (!monitorEnabled) {
            log.debug("备付金监控已禁用，跳过");
            return;
        }

        log.info("备付金监控调度开始");

        // 1. 检查预警级别
        try {
            var alertResults = reserveFundService.checkAllMonitored();
            long warningCount = alertResults.stream()
                    .filter(r -> r.getAlertFlag() != null)
                    .count();
            if (warningCount > 0) {
                log.info("备付金预警检查完成: 共 {} 个商户需要关注", warningCount);
            }
        } catch (Exception e) {
            log.error("备付金预警检查失败: error={}", e.getMessage(), e);
        }

        // 2. 自动补充
        try {
            int replenishCount = reserveFundService.autoReplenish();
            if (replenishCount > 0) {
                log.info("备付金自动补充完成: 共补充 {} 个商户", replenishCount);
            }
        } catch (Exception e) {
            log.error("备付金自动补充失败: error={}", e.getMessage(), e);
        }

        log.info("备付金监控调度完成");
    }
}