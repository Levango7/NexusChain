package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * 黑名单地址规则。
 * <p>
 * 当交易付款方或收款方地址命中黑名单时拦截。
 * </p>
 */
@Component
public class BlacklistRule implements RiskRule {

    private static final String RULE_ID = "BLACKLIST";

    /** 黑名单地址集合（TODO(v2.0.0): 改为外部数据源加载与热更新 — tracked in v2.0.0 roadmap） */
    private Set<String> blacklist;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public boolean check(Object transaction) {
        if (Objects.isNull(transaction) || Objects.isNull(blacklist) || blacklist.isEmpty()) {
            return false;
        }
        if (transaction instanceof RiskTransaction riskTx) {
            return isBlacklisted(riskTx.getPayerAddress()) || isBlacklisted(riskTx.getPayeeAddress());
        }
        return false;
    }

    private boolean isBlacklisted(String address) {
        return address != null && !address.isBlank() && blacklist.contains(address);
    }

    public Set<String> getBlacklist() { return blacklist; }
    public void setBlacklist(Set<String> blacklist) { this.blacklist = blacklist; }
}
