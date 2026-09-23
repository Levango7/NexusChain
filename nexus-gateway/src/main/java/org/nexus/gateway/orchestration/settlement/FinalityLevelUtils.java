package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.model.FinalityStatus;
import java.util.Map;

/**
 * FinalityStatus 级别映射与阈值检查工具（Step 3 审查修复 I1）。
 *
 * <p>FinalityStatus 的 ordinal 不能直接用于级别比较（UNKNOWN=3 > FINALIZED=2 语义错误），
 * 因此使用显级别映射：UNKNOWN=0, OPTIMISTIC=1, FINALIZING=2, FINALIZED=3。</p>
 *
 * <p>供 {@code WebhookDeliveryService} 和 {@code PaymentEventListener} 共用，
 * 避免两处重复定义相同的映射和检查逻辑。</p>
 */
public final class FinalityLevelUtils {

    /** FinalityStatus 显级别映射（不能直接用 ordinal，UNKNOWN 语义应为最低级别）。 */
    public static final Map<FinalityStatus, Integer> FINALITY_LEVEL = Map.of(
            FinalityStatus.UNKNOWN, 0,
            FinalityStatus.OPTIMISTIC, 1,
            FinalityStatus.FINALIZING, 2,
            FinalityStatus.FINALIZED, 3
    );

    private FinalityLevelUtils() {}

    /**
     * 判断给定 finalityStatus 字符串是否应抑制 Webhook 投递。
     *
     * <p>fail-open 原则：未知 finalityStatus 字符串不抑制投递（避免阻断合法通知）。</p>
     *
     * @param finalityStatusStr payload 中的 finalityStatus 字符串
     * @param thresholdStr 配置的阈值字符串（如 "OPTIMISTIC", "FINALIZED"）
     * @return true 表示应抑制投递（finalityStatus 低于阈值）
     */
    public static boolean shouldSuppress(String finalityStatusStr, String thresholdStr) {
        try {
            FinalityStatus status = FinalityStatus.valueOf(finalityStatusStr);
            return !meetsThreshold(status, thresholdStr);
        } catch (IllegalArgumentException e) {
            // 未知状态不抑制（fail-open）
            return false;
        }
    }

    /**
     * 判断给定 FinalityStatus 是否达到配置的阈值级别。
     *
     * @param status 待检查的最终性状态
     * @param thresholdStr 配置的阈值字符串
     * @return true 表示达到或超过阈值
     */
    public static boolean meetsThreshold(FinalityStatus status, String thresholdStr) {
        try {
            FinalityStatus threshold = FinalityStatus.valueOf(thresholdStr);
            return FINALITY_LEVEL.getOrDefault(status, 0) >= FINALITY_LEVEL.getOrDefault(threshold, 3);
        } catch (IllegalArgumentException e) {
            // 阈值配置非法时，默认要求 FINALIZED（最严格）
            return FINALITY_LEVEL.getOrDefault(status, 0) >= FINALITY_LEVEL.get(FinalityStatus.FINALIZED);
        }
    }
}