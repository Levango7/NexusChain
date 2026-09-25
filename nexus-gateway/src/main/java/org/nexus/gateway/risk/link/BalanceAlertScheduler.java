package org.nexus.gateway.risk.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 余额预警定时调度器 — 每 1 分钟定期检查所有启用预警的商户余额。
 *
 * <p>定时任务确保即使没有余额变更事件触发，也能定期检测商户余额
 * 是否低于预警阈值（例如外部因素导致余额变化但未发布事件时）。</p>
 */
@Component
public class BalanceAlertScheduler {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertScheduler.class);

    private final BalanceAlertConfigRepository configRepository;
    private final BalanceAlertService balanceAlertService;

    public BalanceAlertScheduler(BalanceAlertConfigRepository configRepository,
                                  BalanceAlertService balanceAlertService) {
        this.configRepository = configRepository;
        this.balanceAlertService = balanceAlertService;
    }

    /**
     * 每 1 分钟定期检查所有启用预警的商户余额。
     */
    @Scheduled(fixedRate = 60_000)
    public void scheduledBalanceCheck() {
        List<BalanceAlertConfig> configs = configRepository.findByEnabledTrue();
        if (configs.isEmpty()) {
            return;
        }

        log.debug("定时余额预警检查: 共 {} 个商户配置", configs.size());

        for (BalanceAlertConfig config : configs) {
            try {
                AlertLevel level = balanceAlertService.checkBalanceAndAlert(config.getMerchantId());
                if (level != null) {
                    log.info("定时预警检查: merchantId={}, alertLevel={}", config.getMerchantId(), level);
                }
            } catch (Exception e) {
                log.error("定时预警检查失败: merchantId={}, error={}",
                        config.getMerchantId(), e.getMessage(), e);
            }
        }
    }
}