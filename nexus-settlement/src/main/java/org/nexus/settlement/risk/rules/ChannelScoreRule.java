package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 渠道风险评分规则。
 * <p>
 * 根据支付渠道的健康指标评估风险：
 * <ul>
 *   <li>渠道故障率 > 20% → 80</li>
 *   <li>渠道故障率 > 10% → 50</li>
 *   <li>渠道拒付率 > 5% → 70</li>
 *   <li>渠道拒付率 > 2% → 40</li>
 *   <li>正常 → 0</li>
 * </ul>
 * 取所有维度中最高评分作为最终评分。
 * </p>
 *
 * <p>权重：4。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class ChannelScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "CHANNEL_SCORE";

    /** 渠道故障率高风险阈值 */
    private static final double FAILURE_RATE_HIGH = 0.20;

    /** 渠道故障率中风险阈值 */
    private static final double FAILURE_RATE_MEDIUM = 0.10;

    /** 渠道拒付率高风险阈值 */
    private static final double CHARGEBACK_RATE_HIGH = 0.05;

    /** 渠道拒付率中风险阈值 */
    private static final double CHARGEBACK_RATE_MEDIUM = 0.02;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 4;
    }

    @Override
    public String getRuleDescription() {
        return "Channel-based scoring rule: evaluates risk by channel failure rate and chargeback rate";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }

        int maxScore = 0;

        // 渠道故障率评分
        Double failureRate = riskTx.getChannelFailureRate();
        if (failureRate != null) {
            if (failureRate > FAILURE_RATE_HIGH) {
                maxScore = Math.max(maxScore, 80);
            } else if (failureRate > FAILURE_RATE_MEDIUM) {
                maxScore = Math.max(maxScore, 50);
            }
        }

        // 渠道拒付率评分
        Double chargebackRate = riskTx.getChannelChargebackRate();
        if (chargebackRate != null) {
            if (chargebackRate > CHARGEBACK_RATE_HIGH) {
                maxScore = Math.max(maxScore, 70);
            } else if (chargebackRate > CHARGEBACK_RATE_MEDIUM) {
                maxScore = Math.max(maxScore, 40);
            }
        }

        return maxScore;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }
}