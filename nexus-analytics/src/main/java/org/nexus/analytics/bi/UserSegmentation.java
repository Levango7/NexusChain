package org.nexus.analytics.bi;

import java.util.Map;

/**
 * 用户分群服务。
 *
 * <p>基于用户行为特征（交易频次、金额、活跃时段等）将用户归入
 * 预定义分群（如高净值 / 长尾 / 商户 / 沉默）。
 */
public interface UserSegmentation {

    /**
     * 对单个用户执行分群。
     *
     * @param userId 用户 ID
     * @return 分群 ID
     */
    String segment(String userId);

    /**
     * 获取分群画像（统计特征）。
     *
     * @param segmentId 分群 ID
     * @return 分群画像（含 size / avgTxAmount / avgFrequency 等键）
     */
    Map<String, Object> getSegmentProfile(String segmentId);

    /**
     * 列出全部分群 ID。
     *
     * @return 分群 ID 列表
     */
    java.util.List<String> listSegments();
}