package org.nexus.gateway.risk.link;

import org.nexus.gateway.account.AccountBalanceChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 余额预警事件监听器 — 监听 AccountBalanceChangedEvent。
 *
 * <p>当商户账户余额发生变更时，自动触发余额预警检查。
 * 余额变更事件由 AccountService 在每次余额操作后发布。</p>
 *
 * <p>异步执行，避免阻塞余额变更主流程。</p>
 */
@Component
public class BalanceAlertListener {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertListener.class);

    private final BalanceAlertService balanceAlertService;

    public BalanceAlertListener(BalanceAlertService balanceAlertService) {
        this.balanceAlertService = balanceAlertService;
    }

    /**
     * 监听账户余额变更事件 — 触发余额预警检查。
     *
     * @param event 账户余额变更事件
     */
    @Async
    @EventListener
    public void onAccountBalanceChanged(AccountBalanceChangedEvent event) {
        Long merchantId = event.getMerchantId();
        if (merchantId == null) {
            log.debug("余额变更事件无商户 ID，跳过预警检查");
            return;
        }

        log.info("收到余额变更事件，触发预警检查: merchantId={}, operationType={}, balanceAfter={}",
                merchantId, event.getOperationType(), event.getBalanceAfter());

        try {
            AlertLevel level = balanceAlertService.checkBalanceAndAlert(merchantId);
            if (level != null) {
                log.info("余额预警检查完成: merchantId={}, alertLevel={}", merchantId, level);
            }
        } catch (Exception e) {
            log.error("余额预警检查失败: merchantId={}, error={}", merchantId, e.getMessage(), e);
        }
    }
}