package org.nexus.gateway.orchestration.connector;

/**
 * Connector 失败分类。
 *
 * <p><b>资金路径硬规则：分不清时一律归 {@link #DECLINE}（不熔断）。</b>
 * 误判成 {@link #UNAVAILABLE} 会掐掉一个活着的收款通道；误判成 {@link #DECLINE}
 * 只是退化回「每笔都打一遍」的现状。动钱路径上宁可迟钝，不能误伤。</p>
 *
 * <p><b>分类依据只能是结构化信号</b>（HTTP 状态码、异常类型、超时类型），
 * 不得依赖 {@code errorMessage} 文案匹配——PSP 改一句提示就能让匹配静默失效，
 * 那会变成另一种「看起来在工作」。</p>
 *
 * <p>熔断器只计 {@link #UNAVAILABLE}；{@link #DECLINE}、{@link #CONFIG}
 * 以及 {@code null} 一律不计入失败率。</p>
 */
public enum FailureKind {
    /** 业务拒绝（余额不足、卡被拒等）：说明通道活着，绝不能计入熔断。 */
    DECLINE,
    /** 基础设施不可用（超时、连接失败、5xx/429）：唯一计入熔断的分类。 */
    UNAVAILABLE,
    /** 连接器配置缺失/鉴权失败：确定性问题，不计入熔断，避免配置修好后仍被锁在 open 态。 */
    CONFIG;

    /** 是否计入熔断失败。只有 {@link #UNAVAILABLE} 返回 true。 */
    public boolean isBreakerFailure() {
        return this == UNAVAILABLE;
    }

    /** null 安全的判定：null 视为 {@link #DECLINE}，不计入熔断。 */
    public static boolean countsAsBreakerFailure(FailureKind kind) {
        return kind != null && kind.isBreakerFailure();
    }
}
