package org.nexus.gateway.account;

/**
 * 商户虚拟账户类型枚举。
 *
 * <p>商户虚拟账户支持三种类型：</p>
 * <ul>
 *   <li>{@link #BALANCE} — 可用余额账户，商户日常收付款使用</li>
 *   <li>{@link #FROZEN} — 冻结资金账户，担保交易/预授权冻结金额存放</li>
 *   <li>{@link #RESERVE} — 备付金账户，合规要求的储备资金</li>
 * </ul>
 */
public enum AccountType {
    /** 可用余额账户 */
    BALANCE,
    /** 冻结资金账户 */
    FROZEN,
    /** 备付金账户 */
    RESERVE
}