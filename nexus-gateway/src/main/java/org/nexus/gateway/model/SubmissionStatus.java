package org.nexus.gateway.model;

/**
 * 交易提交状态 — 描述一笔链上交易从签名到入块的生命周期阶段。
 *
 * <p>与 {@link FinalityStatus}（最终性状态：描述不可逆程度）正交，
 * {@code SubmissionStatus} 描述的是<strong>交易在链网络中的流转阶段</strong>：</p>
 *
 * <ul>
 *   <li>{@link #SIGNING} — 交易正在签名中（MPC/签名服务处理中）</li>
 *   <li>{@link #SUBMITTED} — 交易已签名并广播到链网络，但尚未入块</li>
 *   <li>{@link #INCLUDED} — 交易已入块，但确认数未达最终化阈值</li>
 *   <li>{@link #REORGED} — 交易所在区块被重组（链分叉回退），需重新提交</li>
 *   <li>{@link #REJECTED} — 交易被链拒绝（签名无效、nonce 冲突、gas 不足等）</li>
 * </ul>
 *
 * <p>典型流转路径：SIGNING → SUBMITTED → INCLUDED →（进入 FinalityStatus 判定）。
 * 异常路径：SUBMITTED → REJECTED；INCLUDED → REORGED → SUBMITTED（重新提交）。</p>
 */
public enum SubmissionStatus {
    /** 交易正在签名中（MPC/签名服务处理中） */
    SIGNING,
    /** 交易已签名并广播到链网络，但尚未入块 */
    SUBMITTED,
    /** 交易已入块，但确认数未达最终化阈值 */
    INCLUDED,
    /** 交易所在区块被重组（链分叉回退），需重新提交 */
    REORGED,
    /** 交易被链拒绝（签名无效、nonce 冲突、gas 不足等） */
    REJECTED
}