package org.nexus.bridge.saga;

/**
 * Saga 实例状态枚举（P2-F2）。
 *
 * <p>状态机流转：</p>
 * <pre>
 *   PENDING ──► EXECUTING ──► COMPLETED
 *                   │              │
 *                   ▼              ▼
 *               COMPENSATING ──► FAILED
 *                                CANCELLED（用户主动取消终态）
 * </pre>
 *
 * <ul>
 *   <li>{@code PENDING} — Saga 已创建，等待执行</li>
 *   <li>{@code EXECUTING} — 正向步骤执行中</li>
 *   <li>{@code COMPENSATING} — 正向步骤失败，执行补偿中</li>
 *   <li>{@code COMPLETED} — 全部正向步骤成功完成</li>
 *   <li>{@code FAILED} — 补偿失败或不可恢复，需人工介入</li>
 *   <li>{@code CANCELLED} — 用户主动取消终态（低3 改进）</li>
 * </ul>
 *
 * @since 2.2.0
 */
public enum SagaState {
    /** 等待执行。 */
    PENDING,
    /** 正向步骤执行中。 */
    EXECUTING,
    /** 补偿中（正向步骤失败回退）。 */
    COMPENSATING,
    /** 全部完成。 */
    COMPLETED,
    /** 失败终态（需人工介入）。 */
    FAILED,
    /**
     * 用户主动取消终态（低3 改进）。
     *
     * <p>用户在 Saga 完成前主动取消（如关闭订单、撤回跨链请求），
     * Saga 进入 CANCELLED 终态，不再参与重试或恢复。
     * 若 Saga 已进入 EXECUTING 且步骤 1 已上链，取消需配合补偿。</p>
     */
    CANCELLED
}