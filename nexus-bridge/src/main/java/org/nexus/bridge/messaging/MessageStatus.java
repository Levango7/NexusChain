package org.nexus.bridge.messaging;

/**
 * 跨链消息状态枚举。
 *
 * <p>描述一条 {@link CrossChainMessage} 在中继与执行生命周期中的状态流转：</p>
 * <pre>
 *   PENDING ─► RELAYED ─► EXECUTED
 *        │         │
 *        └─────────┴──► FAILED
 *                       └──► EXPIRED
 * </pre>
 *
 * <ul>
 *   <li>{@link #PENDING}   — 消息已创建，等待中继签名</li>
 *   <li>{@link #RELAYED}   — 已通过多签验证并中继至目标链</li>
 *   <li>{@link #EXECUTED}  — 目标链已成功执行消息（调用目标合约）</li>
 *   <li>{@link #FAILED}    — 中继或执行失败（签名不足、合约回滚等）</li>
 *   <li>{@link #EXPIRED}   — 超过 {@code message-timeout} 未完成中继，已过期</li>
 * </ul>
 *
 * @since 1.9.2
 */
public enum MessageStatus {

    /** 消息已创建，等待中继签名。 */
    PENDING,

    /** 已通过多签验证并中继至目标链。 */
    RELAYED,

    /** 目标链已成功执行消息。 */
    EXECUTED,

    /** 中继或执行失败。 */
    FAILED,

    /** 超过消息超时窗口未完成中继。 */
    EXPIRED
}