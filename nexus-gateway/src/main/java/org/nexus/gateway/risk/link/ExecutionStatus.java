package org.nexus.gateway.risk.link;

/**
 * 联动执行状态枚举 — 标识风控联动动作的执行结果。
 *
 * <ul>
 *   <li>{@link #PENDING} — 待执行（已创建记录但尚未执行）</li>
 *   <li>{@link #SUCCESS} — 执行成功</li>
 *   <li>{@link #FAILED} — 执行失败（记录错误信息，可重试）</li>
 *   <li>{@link #SKIPPED} — 跳过（幂等检查发现已执行过）</li>
 * </ul>
 */
public enum ExecutionStatus {
    /** 待执行 */
    PENDING,
    /** 执行成功 */
    SUCCESS,
    /** 执行失败 */
    FAILED,
    /** 跳过（幂等去重） */
    SKIPPED
}