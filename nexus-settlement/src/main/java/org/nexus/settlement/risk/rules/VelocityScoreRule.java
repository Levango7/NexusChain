package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 速度评分规则。
 * <p>
 * 使用内存计数器（ConcurrentHashMap）跟踪各主体最近1分钟/1小时的交易次数，
 * 根据频率返回风险评分：
 * <ul>
 *   <li>1分钟内 &gt; 5次 → 80</li>
 *   <li>1分钟内 &gt; 3次 → 50</li>
 *   <li>1小时内 &gt; 10次 → 60</li>
 *   <li>1小时内 &gt; 5次 → 30</li>
 *   <li>其他 → 0</li>
 * </ul>
 * </p>
 *
 * <p>权重：5。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class VelocityScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "VELOCITY_SCORE";

    /** 1分钟毫秒数 */
    private static final long ONE_MINUTE_MILLIS = 60_000L;

    /** 1小时毫秒数 */
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    /** 每个主体的交易时间戳列表（subject -> 交易时间戳列表） */
    private final Map<String, List<Long>> transactionRecords = new ConcurrentHashMap<>();

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 5;
    }

    @Override
    public String getRuleDescription() {
        return "Velocity-based scoring rule: evaluates risk by transaction frequency";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }

        String subject = buildSubjectKey(riskTx);
        if (subject == null) {
            return 0;
        }

        long now = System.currentTimeMillis();

        List<Long> records = transactionRecords.computeIfAbsent(subject, k -> new ArrayList<>());

        // 先记录当前交易时间戳
        synchronized (records) {
            records.add(now);
            // 清理1小时前的过期记录
            records.removeIf(ts -> now - ts > ONE_HOUR_MILLIS);

            // 统计1分钟内和1小时内的交易次数
            int countInOneMinute = 0;
            int countInOneHour = 0;
            for (Long ts : records) {
                if (now - ts <= ONE_MINUTE_MILLIS) {
                    countInOneMinute++;
                }
                if (now - ts <= ONE_HOUR_MILLIS) {
                    countInOneHour++;
                }
            }

            // 评分逻辑（优先匹配更高风险的条件）
            if (countInOneMinute > 5) {
                return 80;
            }
            if (countInOneMinute > 3) {
                return 50;
            }
            if (countInOneHour > 10) {
                return 60;
            }
            if (countInOneHour > 5) {
                return 30;
            }
            return 0;
        }
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }

    /**
     * 构造主体键。商户维度兜底，避免缺字段时规则失效。
     */
    private String buildSubjectKey(RiskTransaction tx) {
        StringBuilder key = new StringBuilder();
        if (tx.getMerchantId() != null) {
            key.append("m:").append(tx.getMerchantId());
        }
        if (tx.getPayerAddress() != null && !tx.getPayerAddress().isBlank()) {
            key.append("|p:").append(tx.getPayerAddress());
        }
        return key.length() == 0 ? null : key.toString();
    }

    /**
     * 清空所有交易记录（主要用于测试）。
     */
    public void clearRecords() {
        transactionRecords.clear();
    }
}