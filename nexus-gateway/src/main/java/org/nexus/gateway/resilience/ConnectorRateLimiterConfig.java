package org.nexus.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Connector 出口限流配置（PSP 侧速率护栏，2026-11-17）。
 *
 * <p>绑定 {@code nexus.resilience.connector-rate-limit} 前缀。<b>默认关闭</b>
 * （{@code enabled=false}）：这是支付主路径的行为变更，只有在运维确认某渠道
 * 有真实出口配额（如 PSP 合同限速 50 TPS）时才显式打开——无真实配额数据时
 * 限流只会把本可成功的支付在本地拦掉。</p>
 *
 * <p>配置形态与 {@link ConnectorCircuitBreakerConfig} 对齐：
 * default-config + instances 按 connector 类型覆盖，未设置字段回退默认。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "nexus.resilience.connector-rate-limit")
public class ConnectorRateLimiterConfig {

    /** 总开关。false（默认）时 {@link ConnectorRateLimiter#tryAcquire} 恒放行。 */
    private boolean enabled = false;

    /** 默认限流参数，所有 connector 未显式覆盖时使用此值。 */
    private RateLimitProperties defaultConfig = new RateLimitProperties();

    /** 按 connector 类型独立覆盖。key = connector 类型（如 "chain", "stripe"）。 */
    private Map<String, RateLimitProperties> instances = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RateLimitProperties getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(RateLimitProperties defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, RateLimitProperties> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, RateLimitProperties> instances) {
        this.instances = instances;
    }

    /** 指定 connector 类型的有效配置：instances 覆盖优先，未设置字段回退 default。 */
    public RateLimitProperties getEffectiveConfig(String connectorType) {
        RateLimitProperties override = connectorType == null ? null : instances.get(connectorType);
        if (override == null) {
            return defaultConfig;
        }
        RateLimitProperties merged = new RateLimitProperties();
        merged.setLimitForPeriod(
                override.getLimitForPeriod() != null ? override.getLimitForPeriod() : defaultConfig.getLimitForPeriod());
        merged.setLimitRefreshPeriod(
                override.getLimitRefreshPeriod() != null ? override.getLimitRefreshPeriod() : defaultConfig.getLimitRefreshPeriod());
        return merged;
    }

    /** 单个限流器的属性（Resilience4j RateLimiter 参数子集）。 */
    public static class RateLimitProperties {

        /**
         * 每个刷新周期内允许的请求数（Resilience4j limitForPeriod）。
         * 默认 100/周期 —— 打开 enabled 但未配具体值时的保守兜底，实际使用必须按
         * PSP 合同配额显式设置。
         */
        private Integer limitForPeriod = 100;

        /** 刷新周期（Resilience4j limitRefreshPeriod）。默认 1s。 */
        private Duration limitRefreshPeriod = Duration.ofSeconds(1);

        public Integer getLimitForPeriod() {
            return limitForPeriod;
        }

        public void setLimitForPeriod(Integer limitForPeriod) {
            this.limitForPeriod = limitForPeriod;
        }

        public Duration getLimitRefreshPeriod() {
            return limitRefreshPeriod;
        }

        public void setLimitRefreshPeriod(Duration limitRefreshPeriod) {
            this.limitRefreshPeriod = limitRefreshPeriod;
        }
    }
}
