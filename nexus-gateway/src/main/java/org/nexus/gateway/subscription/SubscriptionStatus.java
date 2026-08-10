package org.nexus.gateway.subscription;

/**
 * 订阅状态枚举（P4-T8 订阅与循环计费引擎）。
 *
 * <p>状态机：</p>
 * <pre>
 *   TRIAL ──(试用期结束)──→ ACTIVE
 *   ACTIVE ──(扣款失败)──→ PAST_DUE ──(dunning 重试成功)──→ ACTIVE
 *   PAST_DUE ──(dunning 暂停)──→ PAUSED
 *  任意状态 ──(取消)──→ CANCELLED
 * </pre>
 */
public enum SubscriptionStatus {
    /** 试用期内，不扣款。 */
    TRIAL,
    /** 活跃订阅，按周期扣款。 */
    ACTIVE,
    /** 上次扣款失败，进入 dunning 重试流程。 */
    PAST_DUE,
    /** dunning 重试耗尽后暂停，等待商户/客户恢复。 */
    PAUSED,
    /** 已取消，不再扣款。 */
    CANCELLED
}