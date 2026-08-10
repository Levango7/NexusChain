package org.nexus.core.contract;

/**
 * 合约生命周期状态。
 *
 * <p>状态机：{@code ACTIVE → DEPRECATED → DESTROYED}，单向流转。
 * 本期仅 {@code ACTIVE}（注册即创建）；状态变更 API 为后续增强项。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public enum ContractStatus {
    /** 活跃：合约已注册，可被调用。 */
    ACTIVE,
    /** 弃用：合约不再推荐使用，但仍可查询。 */
    DEPRECATED,
    /** 销毁：合约已销毁，记录保留供历史查询（软删除）。 */
    DESTROYED
}