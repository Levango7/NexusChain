package org.nexus.gateway.orchestration.routing;

import java.math.BigDecimal;
import java.util.List;

/**
 * 路由决策上下文 — 封装路由引擎做出决策所需的全部输入信息。
 *
 * <p>Wave 16 多目标路由策略（MULTI_OBJECTIVE）据此上下文计算各候选 connector
 * 的综合评分，选择最优通道。其他策略（PRIORITY/WEIGHT/COST/EXPLICIT）可按需
 * 读取部分字段。</p>
 *
 * <ul>
 *   <li>{@code paymentId}: 支付 ID，用于 A/B 实验分流与决策审计；
 *       null 表示非支付链路调用（不参与实验分流、不落审计）</li>
 *   <li>{@code merchantId}: 商户 ID，用于查询商户级路由偏好与限额</li>
 *   <li>{@code amount}: 交易金额（分），影响金额分层路由（小额/大额阈值）</li>
 *   <li>{@code currency}: 币种，影响 connector 可用性筛选</li>
 *   <li>{@code preferredConnector}: 调用方显式指定的优先 connector（可为 null）</li>
 *   <li>{@code candidateConnectors}: 经规则筛选后的候选 connector 列表</li>
 *   <li>{@code profile}: 模块六解析结果 ResolvedRoutingProfile，使用 Object 占位
 *       以避免 routing 包对 profile 包的循环依赖；可为 null 表示无 profile</li>
 * </ul>
 */
public record RoutingContext(
    String paymentId,
    Long merchantId,
    BigDecimal amount,
    String currency,
    String preferredConnector,
    List<String> candidateConnectors,
    Object profile  // 模块六解析结果 ResolvedRoutingProfile，可为 null
) {
    /**
     * 创建最简路由上下文 — 仅含商户/金额/币种，其余字段为默认值。
     *
     * @param merchantId 商户 ID
     * @param amount     交易金额（分）
     * @param currency   币种
     * @return 不含支付/偏好 connector、候选列表为空、profile 为 null 的上下文
     */
    public static RoutingContext simple(Long merchantId, BigDecimal amount, String currency) {
        return new RoutingContext(null, merchantId, amount, currency, null, List.of(), null);
    }

    /**
     * 创建支付链路路由上下文 — OrchestrationService 在支付创建时使用。
     *
     * @param paymentId         支付 ID
     * @param merchantId        商户 ID
     * @param amount            交易金额（分）
     * @param currency          币种
     * @param preferredConnector 商户显式偏好 connector（可空）
     * @return 候选列表为空、profile 为 null 的上下文（由引擎解析填充语义字段）
     */
    public static RoutingContext ofPayment(String paymentId, Long merchantId, BigDecimal amount,
                                           String currency, String preferredConnector) {
        return new RoutingContext(paymentId, merchantId, amount, currency,
                preferredConnector, List.of(), null);
    }

    /** 派生上下文：携带模块六解析结果。 */
    public RoutingContext withProfile(Object resolvedProfile) {
        return new RoutingContext(paymentId, merchantId, amount, currency,
                preferredConnector, candidateConnectors, resolvedProfile);
    }
}
