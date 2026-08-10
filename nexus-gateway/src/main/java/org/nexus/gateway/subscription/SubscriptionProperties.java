package org.nexus.gateway.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * 订阅引擎配置（P4-T8 订阅与循环计费引擎）。
 *
 * <p>绑定 {@code nexus.subscription.*} 前缀。注意：{@code GatewayConfig}
 * 中已有简单的 {@code SubscriptionConfig}（maxRetry/retryIntervalMinutes），
 * 本类扩展为完整的订阅引擎配置，包含 dunning 策略。为避免与
 * {@code GatewayConfig.SubscriptionConfig} 字段冲突，本类使用独立前缀
 * {@code nexus.subscription.engine.*} 与 {@code nexus.subscription.dunning.*}。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "nexus.subscription")
public class SubscriptionProperties {

    /** 周期扣款调度 cron 表达式。 */
    private String billingCycleCron = "0 0 * * * *";

    /** Dunning 策略配置。 */
    private Dunning dunning = new Dunning();

    public static class Dunning {
        /** 最大重试次数（达到后通知商户）。 */
        private int maxRetries = 3;
        /** 重试间隔（天），如 "1d,3d,7d"。 */
        private String retryIntervals = "1d,3d,7d";
        /** 累计失败次数达到此值后暂停订阅。 */
        private int suspendAfter = 5;

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public String getRetryIntervals() { return retryIntervals; }
        public void setRetryIntervals(String retryIntervals) { this.retryIntervals = retryIntervals; }

        public int getSuspendAfter() { return suspendAfter; }
        public void setSuspendAfter(int suspendAfter) { this.suspendAfter = suspendAfter; }

        /**
         * 解析重试间隔为天数列表。
         *
         * @return 天数列表，如 [1, 3, 7]
         */
        public List<Integer> parseRetryIntervals() {
            return Arrays.stream(retryIntervals.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        if (s.endsWith("d")) {
                            return Integer.parseInt(s.substring(0, s.length() - 1));
                        }
                        return Integer.parseInt(s);
                    })
                    .toList();
        }

        /**
         * 获取第 {@code attempt} 次失败后的重试间隔（天）。
         *
         * @param attempt 失败次数（1-based）
         * @return 重试间隔天数，超出配置长度返回最后一个
         */
        public int retryIntervalDays(int attempt) {
            List<Integer> intervals = parseRetryIntervals();
            if (intervals.isEmpty()) {
                return 1;
            }
            int idx = Math.min(attempt - 1, intervals.size() - 1);
            return intervals.get(Math.max(0, idx));
        }
    }

    public String getBillingCycleCron() { return billingCycleCron; }
    public void setBillingCycleCron(String billingCycleCron) { this.billingCycleCron = billingCycleCron; }

    public Dunning getDunning() { return dunning; }
    public void setDunning(Dunning dunning) { this.dunning = dunning; }
}