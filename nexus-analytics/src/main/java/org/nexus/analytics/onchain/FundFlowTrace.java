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
import java.util.List;

/**
 * 资金流向追踪实体。
 *
 * <p>记录一笔资金从源地址到目标地址的完整流转路径，
 * 路径上每个节点均为一次链上转账。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FundFlowTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 源地址 */
    @JsonProperty("fromAddress")
    private String fromAddress;

    /** 目标地址 */
    @JsonProperty("toAddress")
    private String toAddress;

    /** 路径上经过的地址序列（含起止） */
    @JsonProperty("path")
    private List<String> path;

    /** 路径上每跳的交易哈希 */
    @JsonProperty("txHashes")
    private List<String> txHashes;

    /** 累计流转金额（最小计量单位） */
    @JsonProperty("amount")
    private BigInteger amount;

    /** 起始时间戳 */
    @JsonProperty("startTimestamp")
    private Instant startTimestamp;

    /** 结束时间戳 */
    @JsonProperty("endTimestamp")
    private Instant endTimestamp;

    /** 路径跳数 */
    @JsonProperty("hops")
    private Integer hops;
}