package org.nexus.gateway.tenant;

/**
 * 租户状态枚举（P4-T6 多租户改造）。
 *
 * <p>租户生命周期：{@code ACTIVE}（正常使用）→ {@code SUSPENDED}（暂停，限流或运营手动暂停）
 * → {@code TERMINATED}（终止，数据保留但所有 API 拒绝）。{@code SUSPENDED} 可恢复到
 * {@code ACTIVE}；{@code TERMINATED} 为终态，不可恢复。</p>
 */
public enum TenantStatus {

    /** 正常状态：API 可调用，新订单可创建。 */
    ACTIVE,

    /** 暂停状态：所有 API 拒绝（限流超限或运营手动暂停），可恢复。 */
    SUSPENDED,

    /** 终止状态：所有 API 拒绝，数据保留但不可恢复。 */
    TERMINATED
}