package org.nexus.gateway.risk.link;

/**
 * 风控联动动作枚举 — 定义风控事件触发的账户联动操作类型。
 *
 * <p>每种联动动作对应一种账户操作：</p>
 * <ul>
 *   <li>{@link #FREEZE} — 冻结商户账户金额（BALANCE → FROZEN）</li>
 *   <li>{@link #UNFREEZE} — 解冻商户账户金额（FROZEN → BALANCE）</li>
 *   <li>{@link #STATUS_FROZEN} — 将账户状态变更为 FROZEN</li>
 *   <li>{@link #STATUS_CLOSED} — 将账户状态变更为 CLOSED（终态）</li>
 *   <li>{@link #STATUS_RESTORED} — 将账户状态恢复为 ACTIVE</li>
 * </ul>
 */
public enum LinkAction {
    /** 冻结金额 — 从 BALANCE 账户冻结指定金额到 FROZEN 账户 */
    FREEZE,
    /** 解冻金额 — 从 FROZEN 账户释放指定金额回 BALANCE 账户 */
    UNFREEZE,
    /** 状态冻结 — 将账户状态变更为 FROZEN */
    STATUS_FROZEN,
    /** 状态关闭 — 将账户状态变更为 CLOSED（终态） */
    STATUS_CLOSED,
    /** 状态恢复 — 将账户状态恢复为 ACTIVE */
    STATUS_RESTORED
}