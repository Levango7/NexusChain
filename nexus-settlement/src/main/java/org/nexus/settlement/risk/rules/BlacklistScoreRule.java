package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 黑名单评分规则。
 * <p>
 * 当交易的 payerAddress、payeeAddress 或 merchantId 命中黑名单时返回评分100，
 * 否则返回0。黑名单数据来源为内存 Set，支持动态添加/移除。
 * </p>
 *
 * <p>权重：10（最高权重，黑名单命中应直接高风险）。check() 在 score ≥ 80 时返回 true。</p>
 */
@Component
public class BlacklistScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "BLACKLIST_SCORE";

    /** 黑名单集合（线程安全） */
    private final Set<String> blacklist = ConcurrentHashMap.newKeySet();

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 10;
    }

    @Override
    public String getRuleDescription() {
        return "Blacklist-based scoring rule: evaluates risk by checking addresses/merchant against blacklist";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }
        if (blacklist.isEmpty()) {
            return 0;
        }

        // payerAddress 在黑名单中 → 100
        if (isBlacklisted(riskTx.getPayerAddress())) {
            return 100;
        }
        // payeeAddress 在黑名单中 → 100
        if (isBlacklisted(riskTx.getPayeeAddress())) {
            return 100;
        }
        // merchantId 在黑名单中 → 100
        if (riskTx.getMerchantId() != null && isBlacklisted(String.valueOf(riskTx.getMerchantId()))) {
            return 100;
        }
        return 0;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }

    private boolean isBlacklisted(String value) {
        return value != null && !value.isBlank() && blacklist.contains(value);
    }

    // --- 黑名单管理方法 ---

    /**
     * 添加地址到黑名单。
     *
     * @param address 地址
     */
    public void addToBlacklist(String address) {
        if (address != null && !address.isBlank()) {
            blacklist.add(address);
        }
    }

    /**
     * 从黑名单移除地址。
     *
     * @param address 地址
     */
    public void removeFromBlacklist(String address) {
        blacklist.remove(address);
    }

    /**
     * 获取当前黑名单（不可变视图）。
     *
     * @return 黑名单集合
     */
    public Set<String> getBlacklist() {
        return Collections.unmodifiableSet(new HashSet<>(blacklist));
    }

    /**
     * 设置黑名单（替换原有黑名单）。
     *
     * @param addresses 黑名单地址集合
     */
    public void setBlacklist(Set<String> addresses) {
        blacklist.clear();
        if (addresses != null) {
            blacklist.addAll(addresses);
        }
    }
}