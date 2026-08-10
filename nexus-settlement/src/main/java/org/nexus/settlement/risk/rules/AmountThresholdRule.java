package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 大额交易拦截规则。
 * <p>
 * 当交易金额超过预设阈值时拦截。阈值通过配置项
 * {@code nexus.settlement.risk.amount-threshold} 注入（默认 100000 最小单位）。
 * </p>
 */
@Component
public class AmountThresholdRule implements RiskRule {

    private static final String RULE_ID = "AMOUNT_THRESHOLD";

    /** 大额阈值，可通过配置覆盖 */
    @Value("${nexus.settlement.risk.amount-threshold:100000}")
    private BigDecimal threshold;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public boolean check(Object transaction) {
        if (Objects.isNull(transaction)) {
            return false;
        }
        if (transaction instanceof RiskTransaction riskTx) {
            BigDecimal amount = riskTx.getAmount();
            return amount != null && threshold != null && amount.compareTo(threshold) > 0;
        }
        // 非 RiskTransaction 入参不拦截（由上层决定是否适配其他载体）
        return false;
    }

    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
}
