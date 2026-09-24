package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 交易模式异常检测评分规则。
 * <p>
 * 检测异常交易模式：
 * <ul>
 *   <li>拆单（splitOrderCount ≥ 5） → 80</li>
 *   <li>拆单（splitOrderCount ≥ 3） → 50</li>
 *   <li>刷量/试探性交易（recentTransactionCount ≥ 20） → 90</li>
 *   <li>刷量/试探性交易（recentTransactionCount ≥ 10） → 60</li>
 *   <li>正常 → 0</li>
 * </ul>
 * 取所有维度中最高评分作为最终评分。
 * </p>
 *
 * <p>权重：6。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class TransactionPatternScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "TRANSACTION_PATTERN_SCORE";

    /** 拆单高风险阈值 */
    private static final int SPLIT_ORDER_HIGH = 5;

    /** 拆单中风险阈值 */
    private static final int SPLIT_ORDER_MEDIUM = 3;

    /** 刷量/试探性交易高风险阈值 */
    private static final int RECENT_TX_HIGH = 20;

    /** 刷量/试探性交易中风险阈值 */
    private static final int RECENT_TX_MEDIUM = 10;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 6;
    }

    @Override
    public String getRuleDescription() {
        return "Transaction pattern scoring rule: evaluates risk by split order and burst transaction detection";
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

        // 拆单检测
        Integer splitOrderCount = riskTx.getSplitOrderCount();
        if (splitOrderCount != null) {
            if (splitOrderCount >= SPLIT_ORDER_HIGH) {
                maxScore = Math.max(maxScore, 80);
            } else if (splitOrderCount >= SPLIT_ORDER_MEDIUM) {
                maxScore = Math.max(maxScore, 50);
            }
        }

        // 刷量/试探性交易检测
        Integer recentTransactionCount = riskTx.getRecentTransactionCount();
        if (recentTransactionCount != null) {
            if (recentTransactionCount >= RECENT_TX_HIGH) {
                maxScore = Math.max(maxScore, 90);
            } else if (recentTransactionCount >= RECENT_TX_MEDIUM) {
                maxScore = Math.max(maxScore, 60);
            }
        }

        return maxScore;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }
}