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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(BlacklistRule.class);

    /** 黑名单地址集合（从配置加载，@PostConstruct 初始化——此前无数据源注入，规则空转不生效） */
    private Set<String> blacklist = new java.util.HashSet<>();

    /**
     * 黑名单配置（逗号分隔地址，支持环境变量覆盖）。
     * 例: settlement.risk.blacklist=0xabc123,0xdef456
     */
    @org.springframework.beans.factory.annotation.Value("${settlement.risk.blacklist:}")
    private String blacklistConfig;

    @jakarta.annotation.PostConstruct
    public void loadBlacklist() {
        if (blacklistConfig == null || blacklistConfig.isBlank()) {
            log.warn("BlacklistRule: settlement.risk.blacklist 未配置——规则不生效（空黑名单）");
            return;
        }
        Set<String> loaded = new java.util.HashSet<>();
        for (String addr : blacklistConfig.split(",")) {
            String a = addr.trim();
            if (!a.isEmpty()) {
                loaded.add(a);
            }
        }
        this.blacklist = loaded;
        log.info("BlacklistRule: loaded {} blacklisted addresses from config", loaded.size());
    }

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
