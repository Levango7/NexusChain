package org.nexus.analytics.bi;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

import java.util.List;
import java.util.Map;

/**
 * 统计报告实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatisticsReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 报告唯一标识 */
    @JsonProperty("reportId")
    private String reportId;

    /** 报告类型（如 DAILY / WEEKLY / MONTHLY / CUSTOM） */
    @JsonProperty("reportType")
    private String reportType;

    /** 时间范围起点（ISO-8601） */
    @JsonProperty("rangeStart")
    private Instant rangeStart;

    /** 时间范围终点（ISO-8601） */
    @JsonProperty("rangeEnd")
    private Instant rangeEnd;

    /** 数据点列表，每个点为指标名 → 数值 */
    @JsonProperty("dataPoints")
    private List<Map<String, Object>> dataPoints;

    /** 生成时间 */
    @JsonProperty("generatedAt")
    private Instant generatedAt;

    /** 报告摘要 */
    @JsonProperty("summary")
    private String summary;
}