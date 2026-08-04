package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 大额交易拦截规则骨架。
 * <p>
 * 当交易金额超过预设阈值时拦截。
 * </p>
 */
@Component
public class AmountThresholdRule implements RiskRule {

    private static final String RULE_ID = "AMOUNT_THRESHOLD";

    /** 默认大额阈值（TODO: 改为可配置） */
    private BigDecimal threshold = new BigDecimal("100000");

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public boolean check(Object transaction) {
        // TODO: 从交易对象中提取金额字段并比对阈值
        if (Objects.isNull(transaction)) {
            return false;
        }
        return false;
    }

    public BigDecimal getThreshold() { return threshold; }
    public void setThreshold(BigDecimal threshold) { this.threshold = threshold; }
}