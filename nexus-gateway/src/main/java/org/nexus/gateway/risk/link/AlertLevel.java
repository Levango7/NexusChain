package org.nexus.gateway.risk.link;

/**
 * 预警级别枚举 — 商户账户余额预警的级别分类。
 *
 * <p>预警级别从低到高依次为：</p>
 * <ul>
 *   <li>{@link #WARNING} — 预警级别（余额低于 warning 阈值）</li>
 *   <li>{@link #CRITICAL} — 严重级别（余额低于 critical 阈值）</li>
 *   <li>{@link #EMERGENCY} — 紧急级别（余额低于 emergency 阈值）</li>
 * </ul>
 *
 * <p>预警级别跃迁规则：只允许级别升级（WARNING → CRITICAL → EMERGENCY），
 * 不允许降级。降级需通过人工干预恢复为 NULL（正常）。</p>
 */
public enum AlertLevel {
    /** 预警级别 — 余额低于 warning 阈值 */
    WARNING,
    /** 严重级别 — 余额低于 critical 阈值 */
    CRITICAL,
    /** 紧急级别 — 余额低于 emergency 阈值 */
    EMERGENCY
}