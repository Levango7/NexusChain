package org.nexus.gateway.fundreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自动提现调度器 — 定时扫描启用的自动提现规则并触发执行。
 *
 * <p>调度规则：</p>
 * <ul>
 *   <li>DAILY 规则：每天 02:00 执行一次</li>
 *   <li>WEEKLY 规则：每周一 02:30 执行一次</li>
 *   <li>MONTHLY 规则：每月 1 日 03:00 执行一次</li>
 * </ul>
 *
 * <p>幂等控制：AutoWithdrawService.executeAutoWithdraw 内部基于 lastExecutedAt
 * 判断当前周期是否已执行，确保即使调度器重复触发也不会重复提现。</p>
 */
@Component
public class AutoWithdrawScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoWithdrawScheduler.class);

    private final AutoWithdrawRuleRepository ruleRepository;
    private final AutoWithdrawService autoWithdrawService;

    public AutoWithdrawScheduler(AutoWithdrawRuleRepository ruleRepository,
                                  AutoWithdrawService autoWithdrawService) {
        this.ruleRepository = ruleRepository;
        this.autoWithdrawService = autoWithdrawService;
    }

    /**
     * 每日自动提现调度 — 每天 02:00 执行。
     *
     * <p>扫描所有启用的 DAILY 频率规则，逐条触发执行。
     * 单条规则执行失败不影响其他规则。</p>
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void executeDailyAutoWithdraw() {
        log.info("每日自动提现调度开始: {}", LocalDateTime.now());
        executeByFrequency(WithdrawFrequency.DAILY);
    }

    /**
     * 每周自动提现调度 — 每周一 02:30 执行。
     */
    @Scheduled(cron = "0 30 2 * * MON")
    public void executeWeeklyAutoWithdraw() {
        log.info("每周自动提现调度开始: {}", LocalDateTime.now());
        executeByFrequency(WithdrawFrequency.WEEKLY);
    }

    /**
     * 每月自动提现调度 — 每月 1 日 03:00 执行。
     */
    @Scheduled(cron = "0 0 3 1 * *")
    public void executeMonthlyAutoWithdraw() {
        log.info("每月自动提现调度开始: {}", LocalDateTime.now());
        executeByFrequency(WithdrawFrequency.MONTHLY);
    }

    /**
     * 按频率执行自动提现 — 扫描指定频率的启用规则并逐条执行。
     *
     * @param frequency 提现频率
     */
    private void executeByFrequency(WithdrawFrequency frequency) {
        List<AutoWithdrawRule> rules =
                ruleRepository.findByEnabledAndFrequency(true, frequency);

        if (rules.isEmpty()) {
            log.debug("无 {} 频率的启用规则", frequency);
            return;
        }

        log.info("发现 {} 条 {} 频率的启用规则", rules.size(), frequency);

        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;

        for (AutoWithdrawRule rule : rules) {
            try {
                var result = autoWithdrawService.executeAutoWithdraw(rule);
                Boolean executed = (Boolean) result.get("executed");
                if (Boolean.TRUE.equals(executed)) {
                    successCount++;
                } else {
                    skipCount++;
                }
            } catch (Exception e) {
                failCount++;
                log.error("自动提现执行异常: merchantId={}, ruleId={}, error={}",
                        rule.getMerchantId(), rule.getId(), e.getMessage(), e);
            }
        }

        log.info("{} 频率自动提现调度完成: 成功={}, 跳过={}, 失败={}",
                frequency, successCount, skipCount, failCount);
    }
}