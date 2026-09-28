package org.nexus.gateway.orchestration.routing.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 路由决策审计记录 JPA 实体（Wave 16 模块五）。
 *
 * <p>对应表 {@code routing_decision_records}（V89）。每次 {@code RoutingEngine}
 * 决策落一条记录：输入（金额/币种/商户）、命中规则、策略、候选列表、各候选评分、
 * 最终决策、A/B 实验归属；支付完成后回填 {@code outcome}。</p>
 */
@Entity
@Table(name = "routing_decision_records")
public class RoutingDecisionRecord {

    @Id
    @Column(name = "decision_id", length = 64, nullable = false)
    private String decisionId;

    @Column(name = "payment_id", length = 64, nullable = false)
    private String paymentId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "input_json", length = 1024)
    private String inputJson;

    @Column(name = "rule_matched", length = 64)
    private String ruleMatched;

    @Column(name = "strategy", length = 32, nullable = false)
    private String strategy;

    @Column(name = "candidates_json", length = 1024)
    private String candidatesJson;

    @Column(name = "scores_json", length = 2048)
    private String scoresJson;

    @Column(name = "decision_json", length = 1024)
    private String decisionJson;

    @Column(name = "ab_test_group", length = 32)
    private String abTestGroup;

    @Column(name = "experiment_id", length = 64)
    private String experimentId;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // === Getters & Setters ===

    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String decisionId) { this.decisionId = decisionId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getInputJson() { return inputJson; }
    public void setInputJson(String inputJson) { this.inputJson = inputJson; }

    public String getRuleMatched() { return ruleMatched; }
    public void setRuleMatched(String ruleMatched) { this.ruleMatched = ruleMatched; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public String getCandidatesJson() { return candidatesJson; }
    public void setCandidatesJson(String candidatesJson) { this.candidatesJson = candidatesJson; }

    public String getScoresJson() { return scoresJson; }
    public void setScoresJson(String scoresJson) { this.scoresJson = scoresJson; }

    public String getDecisionJson() { return decisionJson; }
    public void setDecisionJson(String decisionJson) { this.decisionJson = decisionJson; }

    public String getAbTestGroup() { return abTestGroup; }
    public void setAbTestGroup(String abTestGroup) { this.abTestGroup = abTestGroup; }

    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
