package org.nexus.bridge.saga;

/**
 * Saga 实例状态枚举（P2-F2）。
 *
 * <p>状态机流转：</p>
 * <pre>
 *   PENDING ──► EXECUTING ──► COMPLETED
 *                   │
 *                   ▼
 *               COMPENSATING ──► FAILED
 * </pre>
 *
 * <ul>
 *   <li>{@code PENDING} — Saga 已创建，等待执行</li>
 *   <li>{@code EXECUTING} — 正向步骤执行中</li>
 *   <li>{@code COMPENSATING} — 正向步骤失败，执行补偿中</li>
 *   <li>{@code COMPLETED} — 全部正向步骤成功完成</li>
 *   <li>{@code FAILED} — 补偿失败或不可恢复，需人工介入</li>
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
    FAILED
}