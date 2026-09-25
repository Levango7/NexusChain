package org.nexus.gateway.account;

/**
 * 商户虚拟账户状态枚举。
 *
 * <p>账户状态流转：{@code ACTIVE} → {@code FROZEN} → {@code ACTIVE}
 * 或 {@code ACTIVE} → {@code CLOSED}（终态）。</p>
 */
public enum AccountStatus {
    /** 活跃状态 — 可正常操作 */
    ACTIVE,
    /** 冻结状态 — 禁止充值/提现/转账，仅允许查询 */
    FROZEN,
    /** 关闭状态 — 终态，所有操作禁止 */
    CLOSED
}