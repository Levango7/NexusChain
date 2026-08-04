package org.nexus.oracle.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 治理提案实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Proposal implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提案类型 */
    public enum Type {
        /** 链上参数调整（如出块间隔 / 费率） */
        PARAMETER_CHANGE,
        /** 软件升级（节点版本切换） */
        SOFTWARE_UPGRADE,
        /** 国库支出 */
        TREASURY_SPEND
    }

    /** 提案唯一标识 */
    @JsonProperty("proposalId")
    private String proposalId;

    /** 提案标题 */
    @JsonProperty("title")
    private String title;

    /** 提案描述 */
    @JsonProperty("description")
    private String description;

    /** 提案类型 */
    @JsonProperty("type")
    private Type type;

    /** 当前状态 */
    @JsonProperty("state")
    private ProposalState state;

    /** 投票期开始时间 */
    @JsonProperty("votingStart")
    private Instant votingStart;

    /** 投票期时长 */
    @JsonProperty("votingPeriod")
    private Duration votingPeriod;

    /** 通过后到执行之间的延迟 */
    @JsonProperty("executionDelay")
    private Duration executionDelay;

    /** 提案参数（类型相关，如 PARAMETER_CHANGE 的键值对、TREASURY_SPEND 的金额与目标） */
    @JsonProperty("parameters")
    private Map<String, Object> parameters;

    /** 提案发起人 */
    @JsonProperty("proposer")
    private String proposer;
}