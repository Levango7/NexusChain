package org.nexus.gateway.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Connector 级熔断器配置。
 *
 * <p>绑定 {@code nexus.resilience.connector} 前缀，支持默认配置 + 按 connector 类型独立覆盖。
 * 配置示例见 application.yml 的 {@code nexus.resilience.connector} 节。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "nexus.resilience.connector")
public class ConnectorCircuitBreakerConfig {

    /**
     * 默认熔断配置，所有 connector 未显式覆盖时使用此值。
     */
    private CircuitBreakerProperties defaultConfig = new CircuitBreakerProperties();

    /**
     * 按 connector 类型独立覆盖的配置 map。
     * key = connector 类型（如 "chain", "consortium", "stripe"），
     * value = 该类型的熔断参数（未设置的字段回退到 defaultConfig）。
     */
    private Map<String, CircuitBreakerProperties> instances = new HashMap<>();

    public CircuitBreakerProperties getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(CircuitBreakerProperties defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, CircuitBreakerProperties> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, CircuitBreakerProperties> instances) {
        this.instances = instances;
    }

    /**
     * 获取指定 connector 类型的有效配置：先查 instances 覆盖，未覆盖的字段回退到 defaultConfig。
     *
     * @param connectorType connector 类型标识
     * @return 合并后的有效配置
     */
    public CircuitBreakerProperties getEffectiveConfig(String connectorType) {
        CircuitBreakerProperties override = instances.get(connectorType);
        if (override == null) {
            return defaultConfig;
        }
        return mergeWithDefault(override);
    }

    /**
     * 将 override 与 defaultConfig 合并：override 中显式设置的字段优先，未设置的回退到默认值。
     */
    private CircuitBreakerProperties mergeWithDefault(CircuitBreakerProperties override) {
        CircuitBreakerProperties merged = new CircuitBreakerProperties();
        merged.setFailureRateThreshold(
                override.getFailureRateThreshold() != null ? override.getFailureRateThreshold() : defaultConfig.getFailureRateThreshold());
        merged.setWaitDurationInOpenState(
                override.getWaitDurationInOpenState() != null ? override.getWaitDurationInOpenState() : defaultConfig.getWaitDurationInOpenState());
        merged.setSlidingWindowSize(
                override.getSlidingWindowSize() != null ? override.getSlidingWindowSize() : defaultConfig.getSlidingWindowSize());
        merged.setMinimumNumberOfCalls(
                override.getMinimumNumberOfCalls() != null ? override.getMinimumNumberOfCalls() : defaultConfig.getMinimumNumberOfCalls());
        return merged;
    }

    /**
     * 单个熔断器的配置属性。
     */
    public static class CircuitBreakerProperties {

        /** 失败率阈值（百分比），超过此值触发熔断。默认 50。 */
        private Float failureRateThreshold = 50f;

        /** 熔断器 OPEN 状态等待时长，超时后进入 HALF_OPEN。默认 30s。 */
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        /** 滑动窗口大小（调用次数）。默认 10。 */
        private Integer slidingWindowSize = 10;

        /** 最小调用次数：达到此值后才计算失败率。默认 5。 */
        private Integer minimumNumberOfCalls = 5;

        public Float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(Float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public Integer getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(Integer slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public Integer getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(Integer minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }
    }
}