package org.nexus.gateway.compliance;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AML (Anti-Money-Laundering) screening result for a transaction.
 *
 * <p>This POJO captures the outcome of screening a transaction against
 * sanctions lists, PEP (Politically Exposed Persons) lists, and internal
 * watchlists. A non-empty {@link #hitLists} or a {@link #riskScore} above
 * the threshold indicates the transaction requires manual review.</p>
 */
public class AmlResult {

    /** AML risk score from 0 (lowest) to 100 (highest). */
    private Integer riskScore = 0;

    /** Names of the lists that produced hits (e.g. OFAC, EU_SANCTIONS, PEP). */
    private List<String> hitLists = new ArrayList<>();

    /** Whether manual review by a compliance officer is required. */
    private Boolean needsManualReview = false;

    /** Timestamp when the screening was performed. */
    private LocalDateTime screenedAt;

    /** Free-text reason explaining the manual-review trigger, if any. */
    private String reason;

    public AmlResult() {
        this.screenedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public List<String> getHitLists() { return hitLists; }
    public void setHitLists(List<String> hitLists) { this.hitLists = hitLists; }

    public Boolean getNeedsManualReview() { return needsManualReview; }
    public void setNeedsManualReview(Boolean needsManualReview) { this.needsManualReview = needsManualReview; }

    public LocalDateTime getScreenedAt() { return screenedAt; }
    public void setScreenedAt(LocalDateTime screenedAt) { this.screenedAt = screenedAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}