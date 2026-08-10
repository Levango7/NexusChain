package org.nexus.compliance.reputation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 信誉评分实体。
 * <p>
 * 描述地址的当前分数、等级与历史事件。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReputationScore {

    /** 地址 */
    @JsonProperty("address")
    private String address;

    /** 分数 */
    @JsonProperty("score")
    private int score;

    /** 等级 */
    @JsonProperty("grade")
    private Grade grade;

    /** 历史事件 */
    @JsonProperty("historyEvents")
    private List<String> historyEvents;

    /** 信誉等级枚举 */
    public enum Grade {
        A,
        B,
        C,
        D
    }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public Grade getGrade() { return grade; }
    public void setGrade(Grade grade) { this.grade = grade; }

    public List<String> getHistoryEvents() { return historyEvents; }
    public void setHistoryEvents(List<String> historyEvents) { this.historyEvents = historyEvents; }
}