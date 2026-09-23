package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 金额评分规则。
 * <p>
 * 根据交易金额所在区间返回风险评分：
 * <ul>
 *   <li>amount == null → 0</li>
 *   <li>amount &lt; 1,000 → 0</li>
 *   <li>1,000 ≤ amount &lt; 10,000 → 20</li>
 *   <li>10,000 ≤ amount &lt; 100,000 → 40</li>
 *   <li>100,000 ≤ amount &lt; 1,000,000 → 70</li>
 *   <li>amount ≥ 1,000,000 → 90</li>
 * </ul>
 * </p>
 *
 * <p>权重：3。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class AmountScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "AMOUNT_SCORE";

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 3;
    }

    @Override
    public String getRuleDescription() {
        return "Amount-based scoring rule: evaluates risk by transaction amount range";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }
        BigDecimal amount = riskTx.getAmount();
        if (amount == null) {
            return 0;
        }

        int cmp = amount.compareTo(BigDecimal.ZERO);
        if (cmp < 0) {
            return 0;
        }

        // amount < 1,000 → 0
        if (amount.compareTo(new BigDecimal("1000")) < 0) {
            return 0;
        }
        // 1,000 ≤ amount < 10,000 → 20
        if (amount.compareTo(new BigDecimal("10000")) < 0) {
            return 20;
        }
        // 10,000 ≤ amount < 100,000 → 40
        if (amount.compareTo(new BigDecimal("100000")) < 0) {
            return 40;
        }
        // 100,000 ≤ amount < 1,000,000 → 70
        if (amount.compareTo(new BigDecimal("1000000")) < 0) {
            return 70;
        }
        // amount ≥ 1,000,000 → 90
        return 90;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }
}