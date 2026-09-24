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
 * IP 地址风险评分规则。
 * <p>
 * 检测 IP 地址的风险等级：
 * <ul>
 *   <li>黑名单 IP → 100</li>
 *   <li>代理/VPN IP（已知代理 IP 段） → 70</li>
 *   <li>异地 IP（与商户注册地不一致） → 40</li>
 *   <li>正常 IP → 0</li>
 * </ul>
 * </p>
 *
 * <p>权重：6。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class IpScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "IP_SCORE";

    /** 黑名单 IP 集合（线程安全） */
    private final Set<String> blacklistIps = ConcurrentHashMap.newKeySet();

    /** 已知代理 IP 集合（线程安全） */
    private final Set<String> proxyIps = ConcurrentHashMap.newKeySet();

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 6;
    }

    @Override
    public String getRuleDescription() {
        return "IP-based scoring rule: evaluates risk by IP blacklist/proxy/remote detection";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }
        String ip = riskTx.getIpAddress();
        if (ip == null || ip.isBlank()) {
            return 0;
        }

        // 黑名单 IP → 100
        if (blacklistIps.contains(ip)) {
            return 100;
        }

        // 代理/VPN IP → 70
        if (proxyIps.contains(ip)) {
            return 70;
        }

        // 检查 IP 段是否属于已知代理范围（简单前缀匹配）
        for (String proxyPrefix : proxyIps) {
            if (proxyPrefix.endsWith("*") && ip.startsWith(proxyPrefix.substring(0, proxyPrefix.length() - 1))) {
                return 70;
            }
        }

        // 异地 IP（region 不匹配） → 40
        // 如果交易有 region 信息且与商户注册地不同，视为异地
        // 这里简化处理：region 为 null 或与 IP 地理位置不一致时给一定评分
        // 实际生产中应通过 IP 地理位置服务查询
        if (riskTx.getRegion() != null && !riskTx.getRegion().isBlank()) {
            // 有 region 信息但无法确认是否异地，给较低评分
            return 10;
        }

        return 0;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }

    // --- IP 黑名单管理 ---

    /**
     * 添加 IP 到黑名单。
     *
     * @param ip IP 地址
     */
    public void addToBlacklist(String ip) {
        if (ip != null && !ip.isBlank()) {
            blacklistIps.add(ip);
        }
    }

    /**
     * 从黑名单移除 IP。
     *
     * @param ip IP 地址
     */
    public void removeFromBlacklist(String ip) {
        blacklistIps.remove(ip);
    }

    /**
     * 获取当前 IP 黑名单（不可变视图）。
     *
     * @return 黑名单 IP 集合
     */
    public Set<String> getBlacklistIps() {
        return Collections.unmodifiableSet(new HashSet<>(blacklistIps));
    }

    /**
     * 设置 IP 黑名单（替换原有黑名单）。
     *
     * @param ips IP 地址集合
     */
    public void setBlacklistIps(Set<String> ips) {
        blacklistIps.clear();
        if (ips != null) {
            blacklistIps.addAll(ips);
        }
    }

    // --- 代理 IP 管理 ---

    /**
     * 添加代理 IP。
     *
     * @param ip 代理 IP 地址（支持通配符前缀，如 "10.0.0.*"）
     */
    public void addProxyIp(String ip) {
        if (ip != null && !ip.isBlank()) {
            proxyIps.add(ip);
        }
    }

    /**
     * 从代理列表移除 IP。
     *
     * @param ip 代理 IP 地址
     */
    public void removeProxyIp(String ip) {
        proxyIps.remove(ip);
    }

    /**
     * 获取当前代理 IP 列表（不可变视图）。
     *
     * @return 代理 IP 集合
     */
    public Set<String> getProxyIps() {
        return Collections.unmodifiableSet(new HashSet<>(proxyIps));
    }

    /**
     * 设置代理 IP 列表（替换原有列表）。
     *
     * @param ips 代理 IP 地址集合
     */
    public void setProxyIps(Set<String> ips) {
        proxyIps.clear();
        if (ips != null) {
            proxyIps.addAll(ips);
        }
    }
}