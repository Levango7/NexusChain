package org.nexus.oracle.governance;

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
 * 治理投票实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Vote implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 投票选项 */
    public enum Option {
        /** 赞成 */
        YES,
        /** 反对 */
        NO,
        /** 弃权 */
        ABSTAIN
    }

    /** 关联提案 ID */
    @JsonProperty("proposalId")
    private String proposalId;

    /** 投票者地址 */
    @JsonProperty("voter")
    private String voter;

    /** 投票选项 */
    @JsonProperty("option")
    private Option option;

    /** 投票权重（通常为投票者质押量） */
    @JsonProperty("weight")
    private BigInteger weight;

    /** 投票时间戳 */
    @JsonProperty("timestamp")
    private Instant timestamp;

    /** 投票签名（可选，链上投票时填充） */
    @JsonProperty("signature")
    private String signature;
}