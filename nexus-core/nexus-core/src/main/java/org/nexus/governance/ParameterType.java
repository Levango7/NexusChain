package org.nexus.governance;

/**
 * 可治理参数类型枚举。
 *
 * <p>不同类型对应不同的取值域与校验规则：</p>
 * <ul>
 *   <li>{@link #DECIMAL} — 任意精度小数（费率、阈值等）</li>
 *   <li>{@link #DURATION} — 时长（毫秒），用于投票期、时间锁延迟等</li>
 *   <li>{@link #INT} — 整数（批量大小、区块 Gas 上限等）</li>
 *   <li>{@link #BOOL} — 布尔开关</li>
 * </ul>
 *
 * @since 1.3
 */
public enum ParameterType {
    /** 任意精度小数 */
    DECIMAL,
    /** 时长（毫秒） */
    DURATION,
    /** 整数 */
    INT,
    /** 布尔 */
    BOOL
}