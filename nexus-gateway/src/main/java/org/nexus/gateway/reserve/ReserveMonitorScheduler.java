package org.nexus.gateway.reserve;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 *
 * <p><b>为什么这里必须有 ShedLock</b>：第二个任务会真实动账
 * （{@code ReserveFundService#autoReplenish} → {@code accountService.transfer}），
 * 而触发判据只是"余额 &lt; 阈值"，没有认领、没有幂等键、没有"补充中"标记。
 * prod 是 {@code values-prod.yaml:20-21} 的 {@code nexus-gateway.replicaCount: 3}，
 * 不加锁就是三个副本在同一刻读到同一个低余额、各转一笔 ⇒ 备付金被超额补足最多 3 倍。
 * {@code MerchantAccount} 上的 {@code @Version} 只保证余额算术一致，
 * 三次转账在业务上各自都合法，<b>它不是去重</b>。</p>
 *
 * <p><b>为什么锁够用、不需要 CAS 认领</b>：这里没有"指令行"可认领，
 * 判据是共享 DB 余额且补足后会自动抹掉条件（自终止），
 * 所以跨 tick 的重复执行是合法行为，只有同一 tick 的并发才是问题。
 * 彻底封死"锁过期后第二副本进入"这个残留窗口需要幂等键，属后续建议。</p>
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
    @SchedulerLock(name = "reserveMonitorSweep", lockAtMostFor = "PT20M", lockAtLeastFor = "PT2M")
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