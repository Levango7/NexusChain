package org.nexus.gateway.orchestration.routing.profile;

import java.util.List;

/**
 * 路由画像解析结果（Wave 16 模块六）。
 *
 * <p>由 {@link MerchantRoutingProfileService} 解析商户/行业级路由配置后产出，
 * 供 {@code MultiObjectiveRoutingService} 做排除/偏好修正。不可变值对象。</p>
 *
 * @param profileId           命中的配置标识（无命中时为 {@code "default"}）
 * @param merchantId          商户 ID（行业级配置时为 null）
 * @param industry            行业分类（可空）
 * @param amountTier          金额分层：SMALL / MEDIUM / LARGE
 * @param peakHours           是否处于高峰时段（默认 09:00-22:00，可配置）
 * @param preferredObjectives 偏好目标排序（如 LATENCY &gt; COST，信息性字段）
 * @param preferredConnectors 偏好 connector（评分加成）
 * @param excludedConnectors  排除 connector（候选过滤）
 */
public record ResolvedRoutingProfile(
        String profileId,
        Long merchantId,
        String industry,
        String amountTier,
        boolean peakHours,
        List<String> preferredObjectives,
        List<String> preferredConnectors,
        List<String> excludedConnectors) {

    public static final String TIER_SMALL = "SMALL";
    public static final String TIER_MEDIUM = "MEDIUM";
    public static final String TIER_LARGE = "LARGE";
}
