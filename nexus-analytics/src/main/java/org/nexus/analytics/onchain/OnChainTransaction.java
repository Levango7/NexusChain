package org.nexus.analytics.onchain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigInteger;
import java.time.Instant;

/**
 * 链上交易记录。
 *
 * <p>分析模块的统一数据单元：交易图谱、统计、导出均消费本模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnChainTransaction implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 交易哈希 */
    @JsonProperty("txHash")
    private String txHash;

    /** 付款地址 */
    @JsonProperty("fromAddress")
    private String fromAddress;

    /** 收款地址 */
    @JsonProperty("toAddress")
    private String toAddress;

    /** 金额（最小计量单位） */
    @JsonProperty("amount")
    private BigInteger amount;

    /** 交易时间戳 */
    @JsonProperty("timestamp")
    private Instant timestamp;

    /** 交易状态 */
    @JsonProperty("status")
    private Status status;

    /** 关联商户 ID（可选） */
    @JsonProperty("merchantId")
    private String merchantId;

    /** 确认时延（毫秒，可选） */
    @JsonProperty("confirmationLatencyMs")
    private Long confirmationLatencyMs;

    /** 交易状态枚举 */
    public enum Status {
        /** 待确认 */
        PENDING,
        /** 已确认成功 */
        SUCCESS,
        /** 失败 */
        FAILED
    }
}
