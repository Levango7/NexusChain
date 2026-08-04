package org.nexus.analytics.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 告警实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Alert implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 告警级别 */
    public enum Level {
        /** 信息级 */
        INFO,
        /** 警告级 */
        WARN,
        /** 严重级 */
        CRITICAL
    }

    /** 告警状态 */
    public enum State {
        /** 新建未确认 */
        OPEN,
        /** 已确认 */
        ACKNOWLEDGED,
        /** 已恢复 */
        RESOLVED
    }

    /** 告警唯一标识 */
    @JsonProperty("alertId")
    private String alertId;

    /** 告警级别 */
    @JsonProperty("level")
    private Level level;

    /** 告警来源（如 NODE_HEALTH / MEMPOOL / BLOCK_PROPAGATION） */
    @JsonProperty("source")
    private String source;

    /** 告警内容 */
    @JsonProperty("content")
    private String content;

    /** 触发时间戳 */
    @JsonProperty("timestamp")
    private Instant timestamp;

    /** 告警状态 */
    @JsonProperty("state")
    private State state;

    /** 关联指标名（可选） */
    @JsonProperty("metric")
    private String metric;

    /** 关联指标值（可选） */
    @JsonProperty("metricValue")
    private Double metricValue;
}