package org.nexus.oracle.price;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 价格条目实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PriceEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资产符号 */
    @JsonProperty("asset")
    private String asset;

    /** 价格（以法币或计价货币计） */
    @JsonProperty("price")
    private BigDecimal price;

    /** 价格时间戳 */
    @JsonProperty("timestamp")
    private Instant timestamp;

    /** 数据源（如 AGGREGATED / BINANCE / CHAINLINK） */
    @JsonProperty("source")
    private String source;

    /** 置信度，范围 [0.0, 1.0]，由聚合器根据多源一致性评估 */
    @JsonProperty("confidence")
    private Double confidence;
}