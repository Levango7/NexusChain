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
 * 地域风险评分规则。
 * <p>
 * 根据交易地域评估风险：
 * <ul>
 *   <li>高风险地区 → 80</li>
 *   <li>跨境交易 → 60</li>
 *   <li>中等风险地区 → 30</li>
 *   <li>低风险/正常地区 → 0</li>
 * </ul>
 * </p>
 *
 * <p>权重：5。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class RegionScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "REGION_SCORE";

    /** 高风险地区集合（线程安全） */
    private final Set<String> highRiskRegions = ConcurrentHashMap.newKeySet();

    /** 中等风险地区集合（线程安全） */
    private final Set<String> mediumRiskRegions = ConcurrentHashMap.newKeySet();

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 5;
    }

    @Override
    public String getRuleDescription() {
        return "Region-based scoring rule: evaluates risk by geographic region and cross-border detection";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }

        String region = riskTx.getRegion();
        Boolean crossBorder = riskTx.getCrossBorder();

        // 高风险地区 → 80
        if (region != null && !region.isBlank() && highRiskRegions.contains(region)) {
            return 80;
        }

        // 跨境交易 → 60
        if (Boolean.TRUE.equals(crossBorder)) {
            return 60;
        }

        // 中等风险地区 → 30
        if (region != null && !region.isBlank() && mediumRiskRegions.contains(region)) {
            return 30;
        }

        return 0;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }

    // --- 高风险地区管理 ---

    /**
     * 添加高风险地区。
     *
     * @param region 地区代码
     */
    public void addHighRiskRegion(String region) {
        if (region != null && !region.isBlank()) {
            highRiskRegions.add(region);
        }
    }

    /**
     * 移除高风险地区。
     *
     * @param region 地区代码
     */
    public void removeHighRiskRegion(String region) {
        highRiskRegions.remove(region);
    }

    /**
     * 获取高风险地区列表（不可变视图）。
     *
     * @return 高风险地区集合
     */
    public Set<String> getHighRiskRegions() {
        return Collections.unmodifiableSet(new HashSet<>(highRiskRegions));
    }

    /**
     * 设置高风险地区列表。
     *
     * @param regions 地区代码集合
     */
    public void setHighRiskRegions(Set<String> regions) {
        highRiskRegions.clear();
        if (regions != null) {
            highRiskRegions.addAll(regions);
        }
    }

    // --- 中等风险地区管理 ---

    /**
     * 添加中等风险地区。
     *
     * @param region 地区代码
     */
    public void addMediumRiskRegion(String region) {
        if (region != null && !region.isBlank()) {
            mediumRiskRegions.add(region);
        }
    }

    /**
     * 移除中等风险地区。
     *
     * @param region 地区代码
     */
    public void removeMediumRiskRegion(String region) {
        mediumRiskRegions.remove(region);
    }

    /**
     * 获取中等风险地区列表（不可变视图）。
     *
     * @return 中等风险地区集合
     */
    public Set<String> getMediumRiskRegions() {
        return Collections.unmodifiableSet(new HashSet<>(mediumRiskRegions));
    }

    /**
     * 设置中等风险地区列表。
     *
     * @param regions 地区代码集合
     */
    public void setMediumRiskRegions(Set<String> regions) {
        mediumRiskRegions.clear();
        if (regions != null) {
            mediumRiskRegions.addAll(regions);
        }
    }
}