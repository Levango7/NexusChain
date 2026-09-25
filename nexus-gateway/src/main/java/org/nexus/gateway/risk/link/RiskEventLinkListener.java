package org.nexus.gateway.risk.link;

import org.nexus.gateway.risk.RiskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 风险事件联动监听器 — 监听 HIGH/CRITICAL 风险事件。
 *
 * <p>当风控事件的风险评分达到 HIGH（≥60）或 CRITICAL（≥80）级别时，
 * 自动触发账户联动操作：</p>
 *
 * <ul>
 *   <li>HIGH 风险（riskScore ≥ 60 且 < 80）→ 执行 STATUS_FROZEN 联动（冻结账户状态）</li>
 *   <li>CRITICAL 风险（riskScore ≥ 80）→ 执行 STATUS_CLOSED 联动（关闭账户）</li>
 * </ul>
 *
 * <p>注意：此监听器与 {@link RiskAccountLinkListener} 互补，
 * 后者监听 BLOCK 动作（riskDecision），本监听器监听风险评分（riskScore）。</p>
 */
@Component
public class RiskEventLinkListener {

    private static final Logger log = LoggerFactory.getLogger(RiskEventLinkListener.class);

    private static final int HIGH_RISK_THRESHOLD = 60;
    private static final int CRITICAL_RISK_THRESHOLD = 80;

    private final RiskAccountLinkService linkService;

    public RiskEventLinkListener(RiskAccountLinkService linkService) {
        this.linkService = linkService;
    }

    /**
     * 监听风控事件 — 当风险评分达到 HIGH/CRITICAL 时触发账户联动。
     *
     * @param event 风控事件
     */
    @Async
    @EventListener
    public void onRiskEventForLink(RiskEvent event) {
        if (event.getRiskScore() == null) {
            return;
        }

        Integer riskScore = event.getRiskScore();
        Long merchantId = event.getMerchantId();
        String riskEventId = event.getEventId();

        if (merchantId == null) {
            log.debug("风控事件无商户 ID，跳过联动: eventId={}", riskEventId);
            return;
        }

        if (riskScore < HIGH_RISK_THRESHOLD) {
            return;
        }

        log.info("收到高风险事件，触发联动: eventId={}, merchantId={}, riskScore={}",
                riskEventId, merchantId, riskScore);

        try {
            if (riskScore >= CRITICAL_RISK_THRESHOLD) {
                // CRITICAL 风险 → 关闭账户
                linkService.executeStatusChange(riskEventId, merchantId, LinkAction.STATUS_CLOSED,
                        "高风险关闭: riskScore=" + riskScore + ", " + event.getDescription());
            } else {
                // HIGH 风险 → 冻结账户状态
                linkService.executeStatusChange(riskEventId, merchantId, LinkAction.STATUS_FROZEN,
                        "高风险冻结: riskScore=" + riskScore + ", " + event.getDescription());
            }
        } catch (Exception e) {
            log.error("风险事件联动执行失败: eventId={}, merchantId={}, riskScore={}, error={}",
                    riskEventId, merchantId, riskScore, e.getMessage(), e);
        }
    }
}