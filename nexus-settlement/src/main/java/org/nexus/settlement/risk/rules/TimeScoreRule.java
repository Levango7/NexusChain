package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskScoringRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * 时间风险评分规则。
 * <p>
 * 根据交易发生时间评估风险：
 * <ul>
 *   <li>凌晨交易（0:00-5:59） → 70</li>
 *   <li>非营业时间（18:00-23:59） → 40</li>
 *   <li>营业时间（6:00-17:59） → 0</li>
 * </ul>
 * </p>
 *
 * <p>权重：3。check() 在 score ≥ 80 时返回 true（拦截）。</p>
 */
@Component
public class TimeScoreRule implements RiskScoringRule {

    private static final String RULE_ID = "TIME_SCORE";

    /** 营业时间开始（6:00） */
    private static final int BUSINESS_HOUR_START = 6;

    /** 营业时间结束（18:00） */
    private static final int BUSINESS_HOUR_END = 18;

    /** 凌晨时段开始（0:00） */
    private static final int MIDNIGHT_START = 0;

    /** 凌晨时段结束（6:00） */
    private static final int MIDNIGHT_END = 6;

    /** 时区（默认 UTC，可配置） */
    private ZoneId zoneId = ZoneId.of("UTC");

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getWeight() {
        return 3;
    }

    @Override
    public String getRuleDescription() {
        return "Time-based scoring rule: evaluates risk by transaction time (midnight/non-business hours)";
    }

    @Override
    public int score(Object transaction) {
        if (Objects.isNull(transaction)) {
            return 0;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return 0;
        }

        Instant timestamp = riskTx.getTimestamp();
        if (timestamp == null) {
            timestamp = Instant.now();
        }

        ZonedDateTime zdt = timestamp.atZone(zoneId);
        int hour = zdt.getHour();

        // 凌晨交易（0:00-5:59） → 70
        if (hour >= MIDNIGHT_START && hour < MIDNIGHT_END) {
            return 70;
        }

        // 非营业时间（18:00-23:59） → 40
        if (hour >= BUSINESS_HOUR_END) {
            return 40;
        }

        // 营业时间（6:00-17:59） → 0
        return 0;
    }

    @Override
    public boolean check(Object transaction) {
        return score(transaction) >= 80;
    }

    /**
     * 设置时区。
     *
     * @param zoneId 时区 ID
     */
    public void setZoneId(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    /**
     * 获取当前时区。
     *
     * @return 时区 ID
     */
    public ZoneId getZoneId() {
        return zoneId;
    }
}