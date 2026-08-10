package org.nexus.compliance.aml;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 筛查结果实体。
 * <p>
 * 描述一次 AML 筛查的风险等级、命中名单与匹配详情。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScreeningResult {

    /** 风险等级 */
    @JsonProperty("riskLevel")
    private String riskLevel;

    /** 命中名单 */
    @JsonProperty("hitLists")
    private List<String> hitLists;

    /** 匹配详情 */
    @JsonProperty("matchDetails")
    private List<String> matchDetails;

    /** 是否需要人工审核 */
    @JsonProperty("needManualReview")
    private boolean needManualReview;

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public List<String> getHitLists() { return hitLists; }
    public void setHitLists(List<String> hitLists) { this.hitLists = hitLists; }

    public List<String> getMatchDetails() { return matchDetails; }
    public void setMatchDetails(List<String> matchDetails) { this.matchDetails = matchDetails; }

    public boolean isNeedManualReview() { return needManualReview; }
    public void setNeedManualReview(boolean needManualReview) { this.needManualReview = needManualReview; }
}