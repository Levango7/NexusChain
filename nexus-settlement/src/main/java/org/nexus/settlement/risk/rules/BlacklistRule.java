package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/**
 * 黑名单地址规则骨架。
 * <p>
 * 当交易对手地址命中黑名单时拦截。
 * </p>
 */
@Component
public class BlacklistRule implements RiskRule {

    private static final String RULE_ID = "BLACKLIST";

    /** 黑名单地址集合（TODO: 改为外部数据源加载与热更新） */
    private Set<String> blacklist;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public boolean check(Object transaction) {
        // TODO: 从交易对象中提取对手地址并比对黑名单
        if (Objects.isNull(transaction) || Objects.isNull(blacklist)) {
            return false;
        }
        return false;
    }

    public Set<String> getBlacklist() { return blacklist; }
    public void setBlacklist(Set<String> blacklist) { this.blacklist = blacklist; }
}