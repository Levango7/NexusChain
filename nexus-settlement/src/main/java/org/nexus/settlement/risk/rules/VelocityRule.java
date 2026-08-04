package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskRule;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 频率限制规则骨架。
 * <p>
 * 当同一主体在窗口期内交易次数超过阈值时拦截。
 * </p>
 */
@Component
public class VelocityRule implements RiskRule {

    private static final String RULE_ID = "VELOCITY";

    /** 窗口期秒数（TODO: 改为可配置） */
    private long windowSeconds = 60L;

    /** 窗口期内最大允许次数（TODO: 改为可配置） */
    private long maxCount = 10L;

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public boolean check(Object transaction) {
        // TODO: 基于 Redis/本地滑动窗口统计主体在窗口期内的交易次数
        if (Objects.isNull(transaction)) {
            return false;
        }
        return false;
    }

    public long getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }

    public long getMaxCount() { return maxCount; }
    public void setMaxCount(long maxCount) { this.maxCount = maxCount; }
}