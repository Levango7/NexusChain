package org.nexus.governance;

/**
 * 参数敏感度枚举。
 *
 * <p>敏感度决定时间锁延迟长度与变更审计强度：</p>
 * <ul>
 *   <li>{@link #LOW} — 低敏感度，timelock 1 天</li>
 *   <li>{@link #MEDIUM} — 中敏感度，timelock 2 天</li>
 *   <li>{@link #HIGH} — 高敏感度，timelock 7 天</li>
 * </ul>
 *
 * @since 1.3
 */
public enum ParameterSensitivity {
    /** 低敏感度 */
    LOW,
    /** 中敏感度 */
    MEDIUM,
    /** 高敏感度 */
    HIGH
}