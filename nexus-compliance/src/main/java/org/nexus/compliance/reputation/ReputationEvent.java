package org.nexus.compliance.reputation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * 信誉事件实体。
 * <p>
 * 描述一次影响地址信誉评分的事件。事件类型决定加减分方向与幅度，
 * 由 {@link ReputationService#updateScore} 消费。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReputationEvent {

    /** 事件类型 */
    @JsonProperty("type")
    private EventType type;

    /** 事件描述 */
    @JsonProperty("description")
    private String description;

    /** 事件时间 */
    @JsonProperty("occurredAt")
    private Instant occurredAt;

    /** 事件类型枚举 */
    public enum EventType {
        /** 正常支付完成，加分 */
        PAYMENT_COMPLETED,
        /** 商户按时结算，加分 */
        SETTLEMENT_ON_TIME,
        /** 争议 / 退款纠纷，减分 */
        DISPUTE,
        /** 风控拦截，减分 */
        RISK_BLOCKED,
        /** AML 高风险命中，大幅减分 */
        AML_HIGH_RISK,
        /** KYC 升级认证，加分 */
        KYC_UPGRADED
    }

    public ReputationEvent() {}

    public ReputationEvent(EventType type, String description) {
        this.type = type;
        this.description = description;
        this.occurredAt = Instant.now();
    }

    public EventType getType() { return type; }
    public void setType(EventType type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
