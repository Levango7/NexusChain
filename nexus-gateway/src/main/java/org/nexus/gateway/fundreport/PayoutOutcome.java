package org.nexus.gateway.fundreport;

/**
 * 出款结果（P0 修复 2026-10-27）。
 *
 * <p><b>为什么需要这个类型</b>：{@link PayoutExecutor} 的旧契约把「结果未知」写成
 * 文档约定（"不得抛异常、必须返回可区分表示"），但 {@code String} 返回值
 * <b>表达不了</b>这个状态，{@link AutoWithdrawService} 也没有对应分支——
 * 契约只停在注释里，没有任何机制兜住未来的银行代付/渠道转账实现。
 * 本类型把该契约<b>升级为类型级</b>：出款只有两种可表达的结果——</p>
 *
 * <ul>
 *   <li>{@link #executed(String)} — 确定出款成功，携带凭据号；</li>
 *   <li>{@link #unverified(String)} — <b>已提交但结果未知</b>（超时 / 响应丢失 /
 *       待确认）。调用方必须走「保留认领 + 人工核对」，<b>绝不</b>退余额、绝不重试。</li>
 * </ul>
 *
 * <p><b>反面形态（本类型要消灭的）</b>：渠道已受理但响应丢失时——</p>
 * <ul>
 *   <li>抛 {@link PayoutException} ⇒ 调用方退回余额 + 清认领 + 下周期重试 ⇒ <b>二次打款</b>；</li>
 *   <li>返回伪造凭据号 ⇒ 调用方按成功推进周期 ⇒ 无出款证据的"假成功"，
 *       资金去向靠人工兜底。</li>
 * </ul>
 *
 * @param reference  出款凭据号（渠道单号 / 银行流水号 / 人工核销单号）；
 *                   {@code unverified} 时为 null（尚无凭据）
 * @param unverified 是否为「结果未知」
 * @param detail     {@code unverified} 时的原因说明（用于日志 / 审计 / 人工核对），
 *                   否则为 null
 */
public record PayoutOutcome(String reference, boolean unverified, String detail) {

    /** 确定出款成功。 */
    public static PayoutOutcome executed(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException(
                    "出款成功必须携带凭据号；结果无法确定时用 unverified(...)");
        }
        return new PayoutOutcome(reference, false, null);
    }

    /**
     * 结果未知（已提交但拿不到确定的应答）。
     *
     * <p>调用方契约：保留出款认领（fail-closed 拒绝重跑）、<b>不</b>退回余额、
     * 记 {@link AutoWithdrawService#PAYOUT_CLAIM_UNRESOLVED_MARKER} 告警交人工核对。</p>
     */
    public static PayoutOutcome unverified(String detail) {
        return new PayoutOutcome(null, true, detail);
    }

    public boolean isUnverified() {
        return unverified;
    }
}
