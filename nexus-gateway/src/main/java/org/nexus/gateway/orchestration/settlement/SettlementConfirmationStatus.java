package org.nexus.gateway.orchestration.settlement;

/**
 * 链上结算确认状态枚举。
 *
 * <p>描述一笔链上结算交易从发起到最终确认的生命周期状态：</p>
 * <ul>
 *   <li>{@link #PENDING} — 确认流程已启动，等待链上确认数达到阈值</li>
 *   <li>{@link #CONFIRMED} — 链上确认数已达到所需阈值，结算已确认</li>
 *   <li>{@link #TIMED_OUT} — 确认超时（超过指定时间未达到所需确认数），待重试</li>
 *   <li>{@link #FAILED} — 重试次数超过上限，确认失败</li>
 * </ul>
 */
public enum SettlementConfirmationStatus {
    PENDING,
    CONFIRMED,
    TIMED_OUT,
    FAILED
}