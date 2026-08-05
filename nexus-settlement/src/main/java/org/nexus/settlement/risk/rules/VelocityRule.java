package org.nexus.settlement.risk.rules;

import org.nexus.settlement.risk.RiskRule;
import org.nexus.settlement.risk.RiskTransaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 频率限制规则。
 * <p>
 * 当同一主体（商户 + 付款方地址）在滑动窗口期内交易次数超过阈值时拦截。
 * </p>
 *
 * <p>当前实现为进程内滑动窗口（每个主体一个时间戳队列）。
 * 多实例部署时需替换为 Redis 滑动窗口（如 ZSET + ZREMRANGEBYSCORE）以保证全局一致。</p>
 */
@Component
public class VelocityRule implements RiskRule {

    private static final String RULE_ID = "VELOCITY";

    /** 窗口期秒数，可通过配置覆盖 */
    @Value("${nexus.settlement.risk.velocity-window-seconds:60}")
    private long windowSeconds;

    /** 窗口期内最大允许次数，可通过配置覆盖 */
    @Value("${nexus.settlement.risk.velocity-max-count:10}")
    private long maxCount;

    /** 每个主体的滑动窗口时间戳队列（subject -> 交易时间戳队列） */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public boolean check(Object transaction) {
        if (Objects.isNull(transaction)) {
            return false;
        }
        if (!(transaction instanceof RiskTransaction riskTx)) {
            return false;
        }

        // 主体维度：优先用商户+付款方，缺失时退化为商户维度
        String subject = buildSubjectKey(riskTx);
        if (subject == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;

        Deque<Long> queue = windows.computeIfAbsent(subject, k -> new ArrayDeque<>());
        synchronized (queue) {
            // 清理窗口外的过期时间戳
            while (!queue.isEmpty() && now - queue.peekFirst() > windowMillis) {
                queue.pollFirst();
            }
            // 当前窗口内次数已达上限（含本笔）则拦截；否则记账放行
            if (queue.size() >= maxCount) {
                return true;
            }
            queue.addLast(now);
            return false;
        }
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

    public long getWindowSeconds() { return windowSeconds; }
    public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }

    public long getMaxCount() { return maxCount; }
    public void setMaxCount(long maxCount) { this.maxCount = maxCount; }
}
