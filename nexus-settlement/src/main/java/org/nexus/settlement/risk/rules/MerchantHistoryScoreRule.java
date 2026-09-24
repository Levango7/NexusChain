package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 商户历史风险评分规则。
 * <p>
 * 根据商户历史表现评估风险：
 * <ul>
 *   <li>投诉率 > 10% → 80</li>
 *   <li>投诉率 > 5% → 50</li>
 *   <li>退款率 > 15% → 60</li>
 *   <li>退款率 > 8% → 30</li>
 *   <li>违规记录 ≥ 3 次 → 70</li>
 *   <li>违规记录 ≥ 1 次 → 40</li>
 *   <li>正常 → 0</li>
 * </ul>
 * 取所有维度中最高评分作为最终评分。
 * </p>
 *
 * <p>权重：7。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class MerchantHistoryScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "MERCHANT_HISTORY_SCORE";

    /** 投诉率高风险阈值 */
    private static final double COMPLAINT_RATE_HIGH = 0.10;

    /** 投诉率中风险阈值 */
    private static final double COMPLAINT_RATE_MEDIUM = 0.05;

    /** 退款率高风险阈值 */
    private static final double REFUND_RATE_HIGH = 0.15;

    /** 退款率中风险阈值 */
    private static final double REFUND_RATE_MEDIUM = 0.08;

    /** 违规记录高风险阈值 */
    private static final int VIOLATION_COUNT_HIGH = 3;

    /** 违规记录中风险阈值 */
    private static final int VIOLATION_COUNT_MEDIUM = 1;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 7;
    }

    @Override
    public String getRuleDescription() {
        return "Merchant history scoring rule: evaluates risk by complaint rate, refund rate, violation count";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }

        int maxScore = 0;

        // 投诉率评分
        Double complaintRate = riskTx.getMerchantComplaintRate();
        if (complaintRate != null) {
            if (complaintRate > COMPLAINT_RATE_HIGH) {
                maxScore = Math.max(maxScore, 80);
            } else if (complaintRate > COMPLAINT_RATE_MEDIUM) {
                maxScore = Math.max(maxScore, 50);
            }
        }

        // 退款率评分
        Double refundRate = riskTx.getMerchantRefundRate();
        if (refundRate != null) {
            if (refundRate > REFUND_RATE_HIGH) {
                maxScore = Math.max(maxScore, 60);
            } else if (refundRate > REFUND_RATE_MEDIUM) {
                maxScore = Math.max(maxScore, 30);
            }
        }

        // 违规记录评分
        Integer violationCount = riskTx.getMerchantViolationCount();
        if (violationCount != null) {
            if (violationCount >= VIOLATION_COUNT_HIGH) {
                maxScore = Math.max(maxScore, 70);
            } else if (violationCount >= VIOLATION_COUNT_MEDIUM) {
                maxScore = Math.max(maxScore, 40);
            }
        }

        return maxScore;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }
}