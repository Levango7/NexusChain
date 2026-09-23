package org.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConnectorCircuitBreaker} 单元测试 — 测试熔断器状态转换与核心行为。
 */
class ConnectorCircuitBreakerTest {

    private ConnectorCircuitBreakerConfig config;
    private ConnectorCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        config = new ConnectorCircuitBreakerConfig();
        // 配置低阈值以便快速触发熔断
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties defaultProps =
                config.getDefaultConfig();
        defaultProps.setFailureRateThreshold(50f);
        defaultProps.setSlidingWindowSize(4);
        defaultProps.setMinimumNumberOfCalls(2);
        defaultProps.setWaitDurationInOpenState(java.time.Duration.ofSeconds(1));

        circuitBreaker = new ConnectorCircuitBreaker(config);
    }

    @Test
    @DisplayName("新建熔断器初始状态为 CLOSED")
    void newCircuitBreaker_isClosed() {
        CircuitBreaker.State state = circuitBreaker.getConnectorState("chain");
        assertEquals(CircuitBreaker.State.DISABLED, state,
                "未创建的 connector 状态应为 DISABLED");

        // 触发创建
        circuitBreaker.getOrCreate("chain");
        state = circuitBreaker.getConnectorState("chain");
        assertEquals(CircuitBreaker.State.CLOSED, state,
                "新创建的熔断器应为 CLOSED");
    }

    @Test
    @DisplayName("连续失败达到阈值后状态转换为 OPEN")
    void consecutiveFailures_openCircuit() {
        circuitBreaker.getOrCreate("stripe");

        // slidingWindowSize=4, minimumNumberOfCalls=2, failureRateThreshold=50%
        // 2次失败 + 0次成功 = 100% 失败率 > 50% → OPEN
        circuitBreaker.recordFailure("stripe");
        circuitBreaker.recordFailure("stripe");

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getConnectorState("stripe"),
                "连续失败后熔断器应为 OPEN");
    }

    @Test
    @DisplayName("成功调用保持 CLOSED 状态")
    void successCall_staysClosed() {
        circuitBreaker.getOrCreate("consortium");

        circuitBreaker.recordSuccess("consortium");
        circuitBreaker.recordSuccess("consortium");

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("consortium"),
                "成功调用后熔断器应保持 CLOSED");
    }

    @Test
    @DisplayName("混合调用（成功+失败）低于阈值保持 CLOSED")
    void mixedCalls_belowThreshold_staysClosed() {
        // 调整阈值：60% 失败率才触发
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties stripeProps = new ConnectorCircuitBreakerConfig.CircuitBreakerProperties();
        stripeProps.setFailureRateThreshold(60f);
        stripeProps.setSlidingWindowSize(4);
        stripeProps.setMinimumNumberOfCalls(2);
        config.getInstances().put("stripe", stripeProps);

        circuitBreaker.getOrCreate("stripe");

        // 1成功 + 1失败 = 50% 失败率 < 60% → CLOSED
        circuitBreaker.recordSuccess("stripe");
        circuitBreaker.recordFailure("stripe");

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("stripe"),
                "失败率低于阈值时应保持 CLOSED");
    }

    @Test
    @DisplayName("isCircuitOpen 正确判断熔断状态")
    void isCircuitOpen_correctStatus() {
        circuitBreaker.getOrCreate("chain");
        assertFalse(circuitBreaker.isCircuitOpen("chain"),
                "CLOSED 状态时 isCircuitOpen 应为 false");

        circuitBreaker.recordFailure("chain");
        circuitBreaker.recordFailure("chain");

        assertTrue(circuitBreaker.isCircuitOpen("chain"),
                "OPEN 状态时 isCircuitOpen 应为 true");
    }

    @Test
    @DisplayName("reset 将熔断器强制恢复到 CLOSED")
    void reset_returnsToClosed() {
        circuitBreaker.getOrCreate("chain");

        // 触发熔断
        circuitBreaker.recordFailure("chain");
        circuitBreaker.recordFailure("chain");
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getConnectorState("chain"));

        // 重置
        circuitBreaker.reset("chain");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("chain"),
                "reset 后熔断器应为 CLOSED");
    }

    @Test
    @DisplayName("getRegisteredConnectors 返回所有已注册的 connector 类型")
    void getRegisteredConnectors_returnsAll() {
        circuitBreaker.getOrCreate("chain");
        circuitBreaker.getOrCreate("stripe");
        circuitBreaker.getOrCreate("consortium");

        assertTrue(circuitBreaker.getRegisteredConnectors().contains("chain"));
        assertTrue(circuitBreaker.getRegisteredConnectors().contains("stripe"));
        assertTrue(circuitBreaker.getRegisteredConnectors().contains("consortium"));
        assertEquals(3, circuitBreaker.getRegisteredConnectors().size());
    }

    @Test
    @DisplayName("按 connector 类型独立覆盖配置生效")
    void perConnectorOverride_takesEffect() {
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties chainProps = new ConnectorCircuitBreakerConfig.CircuitBreakerProperties();
        chainProps.setFailureRateThreshold(30f);
        chainProps.setWaitDurationInOpenState(java.time.Duration.ofSeconds(60));
        config.getInstances().put("chain", chainProps);

        ConnectorCircuitBreakerConfig.CircuitBreakerProperties effective = config.getEffectiveConfig("chain");
        assertEquals(30f, effective.getFailureRateThreshold(), "chain 覆盖的 failureRateThreshold 应为 30");
        assertEquals(60, effective.getWaitDurationInOpenState().getSeconds(), "chain 覆盖的 waitDuration 应为 60s");
        // 未覆盖的字段回退到默认值
        assertEquals(10, effective.getSlidingWindowSize(), "未覆盖的 slidingWindowSize 应回退到默认值 10");
    }

    @Test
    @DisplayName("未配置覆盖的 connector 使用默认配置")
    void noOverride_usesDefault() {
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties effective = config.getEffectiveConfig("unknown_connector");
        assertEquals(50f, effective.getFailureRateThreshold(), "默认 failureRateThreshold 应为 50");
        assertEquals(1, effective.getWaitDurationInOpenState().getSeconds(), "默认 waitDuration 应为 1s（setUp 中修改）");
    }
}