package org.nexus.gateway.fundreport;

/**
 * 自动提现频率枚举。
 *
 * <p>定义自动提现规则的执行频率，由 {@link AutoWithdrawScheduler} 根据频率调度执行。</p>
 */
public enum WithdrawFrequency {
    /** 每日自动提现 */
    DAILY,
    /** 每周自动提现 */
    WEEKLY,
    /** 每月自动提现 */
    MONTHLY
}