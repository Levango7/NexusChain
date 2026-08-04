package org.nexus.analytics.onchain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 地址聚类实体。
 *
 * <p>表示通过启发式规则（如共同输入、找零模式、行为指纹）识别出的
 * 可能属于同一实体的地址集合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AddressCluster implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 簇唯一标识 */
    @JsonProperty("clusterId")
    private String clusterId;

    /** 簇内地址列表 */
    @JsonProperty("addresses")
    private List<String> addresses;

    /** 聚类标签（如 EXCHANGE / WALLET / MIXER / UNKNOWN） */
    @JsonProperty("label")
    private String label;

    /** 聚类置信度，范围 [0.0, 1.0] */
    @JsonProperty("confidence")
    private Double confidence;

    /** 该簇累计交易笔数 */
    @JsonProperty("txCount")
    private Long txCount;

    /** 该簇累计流转金额（单位：最小计量单位） */
    @JsonProperty("totalVolume")
    private Long totalVolume;
}