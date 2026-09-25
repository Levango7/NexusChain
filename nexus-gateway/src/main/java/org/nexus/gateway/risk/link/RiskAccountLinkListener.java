package org.nexus.gateway.risk.link;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.risk.RiskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 风控联动事件监听器 — 监听风控 BLOCK 动作事件。
 *
 * <p>当风控系统对商户执行 BLOCK（阻断）动作时，自动触发账户冻结联动。
 * BLOCK 动作对应的风控决策为 REJECTED 或 FROZEN。</p>
 *
 * <p>联动逻辑：</p>
 * <ul>
 *   <li>风控决策为 FROZEN → 执行 FREEZE 联动（冻结商户全部余额）</li>
 *   <li>风控决策为 REJECTED → 执行 STATUS_FROZEN 联动（冻结商户账户状态）</li>
 * </ul>
 */
@Component
public class RiskAccountLinkListener {

    private static final Logger log = LoggerFactory.getLogger(RiskAccountLinkListener.class);

    private final RiskAccountLinkService linkService;
    private final AccountService accountService;

    public RiskAccountLinkListener(RiskAccountLinkService linkService,
                                     AccountService accountService) {
        this.linkService = linkService;
        this.accountService = accountService;
    }

    /**
     * 监听风控事件 — 当风控决策为 BLOCK 类动作时触发账户联动。
     *
     * <p>触发条件：riskDecision 为 REJECTED 或 FROZEN。</p>
     *
     * @param event 风控事件
     */
    @Async
    @EventListener
    public void onRiskEvent(RiskEvent event) {
        if (event.getRiskDecision() == null) {
            return;
        }

        String decision = event.getRiskDecision();
        Long merchantId = event.getMerchantId();
        String riskEventId = event.getEventId();

        if (merchantId == null) {
            log.debug("风控事件无商户 ID，跳过联动: eventId={}", riskEventId);
            return;
        }

        log.info("收到风控事件，检查是否需要联动: eventId={}, merchantId={}, decision={}",
                riskEventId, merchantId, decision);

        try {
            if ("FROZEN".equals(decision)) {
                // FROZEN 决策 → 冻结商户全部可用余额
                BigDecimal freezeAmount = determineFreezeAmount(merchantId);
                if (freezeAmount != null && freezeAmount.compareTo(BigDecimal.ZERO) > 0) {
                    linkService.executeFreeze(riskEventId, merchantId, freezeAmount,
                            "风控冻结: " + event.getDescription());
                } else {
                    log.warn("风控冻结联动跳过：商户可用余额为零或查询失败: merchantId={}", merchantId);
                }
            } else if ("REJECTED".equals(decision)) {
                // REJECTED 决策 → 冻结商户账户状态
                linkService.executeStatusChange(riskEventId, merchantId, LinkAction.STATUS_FROZEN,
                        "风控阻断: " + event.getDescription());
            }
        } catch (Exception e) {
            log.error("风控联动执行失败: eventId={}, merchantId={}, decision={}, error={}",
                    riskEventId, merchantId, decision, e.getMessage(), e);
        }
    }

    /**
     * 确定冻结金额 — 查询商户 BALANCE 账户的全部可用余额。
     *
     * <p>通过 {@link AccountService#getBalance} 查询商户 BALANCE 账户的可用余额，
     * 返回该金额作为冻结联动的目标金额。</p>
     *
     * @param merchantId 商户 ID
     * @return 冻结金额（商户全部可用余额），查询失败时返回 null
     */
    private BigDecimal determineFreezeAmount(Long merchantId) {
        try {
            Map<String, Object> balanceInfo = accountService.getBalance(merchantId);
            Object balanceObj = balanceInfo.get("balance");
            if (balanceObj instanceof BigDecimal balance) {
                return balance;
            }
            if (balanceObj != null) {
                return new BigDecimal(balanceObj.toString());
            }
            return BigDecimal.ZERO;
        } catch (Exception e) {
            log.error("查询商户可用余额失败: merchantId={}, error={}", merchantId, e.getMessage(), e);
            return null;
        }
    }
}