package org.nexus.gateway.sla;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SlaMonitorService 单元测试。
 *
 * <p>测试各类型 SLA 指标的计算逻辑（AVAILABILITY/LATENCY/THROUGHPUT/ERROR_RATE），
 * 以及达标判断和 SlaBreachEvent 发布行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class SlaMonitorServiceTest {

    @Mock
    private SlaTargetRepository targetRepository;

    @Mock
    private SlaMeasurementRepository measurementRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private MeterRegistry meterRegistry;
    private SlaMonitorService slaMonitorService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        slaMonitorService = new SlaMonitorService(
                targetRepository, measurementRepository, meterRegistry, eventPublisher);
    }

    // === AVAILABILITY 测试 ===

    @Test
    @DisplayName("AVAILABILITY: 成功请求占比计算正确")
    void testAvailabilityCalculation() {
        // 准备指标数据：90 次成功，10 次失败 → 90% 可用性
        Counter successCounter = Counter.builder("nexus.payments.confirmed")
                .register(meterRegistry);
        Counter failureCounter = Counter.builder("nexus.payments.failed")
                .register(meterRegistry);
        successCounter.increment(90);
        failureCounter.increment(10);

        SlaTarget target = createTarget("Payment API Availability", "nexus.payments",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(90.0, measurement.getMeasuredValue(), 0.01,
                "可用性应为 90%（90次成功 / 100次总计）");
        assertFalse(measurement.isMet(), "90% < 99.9% 目标，应未达标");
        assertEquals(99.9, measurement.getTargetValue(), 0.001);
    }

    @Test
    @DisplayName("AVAILABILITY: 无请求数据时返回 100%（视为可用）")
    void testAvailabilityNoData() {
        SlaTarget target = createTarget("Payment API Availability", "nexus.payments",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(100.0, measurement.getMeasuredValue(), 0.01,
                "无请求数据时应返回 100%");
        assertTrue(measurement.isMet(), "100% >= 99.9% 目标，应达标");
    }

    @Test
    @DisplayName("AVAILABILITY: 全部成功时 100% 可用性")
    void testAvailabilityAllSuccess() {
        Counter successCounter = Counter.builder("nexus.payments.confirmed")
                .register(meterRegistry);
        successCounter.increment(100);

        SlaTarget target = createTarget("Payment API Availability", "nexus.payments",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(100.0, measurement.getMeasuredValue(), 0.01);
        assertTrue(measurement.isMet());
    }

    // === LATENCY 测试 ===

    @Test
    @DisplayName("LATENCY: P99 延迟值计算正确（纳秒转毫秒）")
    void testLatencyCalculation() {
        // 注册 Timer 并记录一些延迟值
        Timer timer = Timer.builder("nexus.payment.latency")
                .register(meterRegistry);
        timer.record(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        timer.record(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        timer.record(500, java.util.concurrent.TimeUnit.MILLISECONDS);

        SlaTarget target = createTarget("Payment Latency P99", "nexus.payment.latency",
                5000, SlaTarget.TargetType.LATENCY, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertTrue(measurement.getMeasuredValue() > 0, "延迟值应大于 0");
        assertTrue(measurement.getMeasuredValue() <= 5000,
                "P99 延迟应 <= 5000ms 目标值");
        assertTrue(measurement.isMet(), "延迟值 <= 目标值，应达标");
    }

    @Test
    @DisplayName("LATENCY: 无 Timer 数据时返回 0")
    void testLatencyNoData() {
        SlaTarget target = createTarget("Payment Latency P99", "nexus.payment.latency",
                5000, SlaTarget.TargetType.LATENCY, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(0.0, measurement.getMeasuredValue(), 0.01);
        assertTrue(measurement.isMet(), "0ms <= 5000ms 目标，应达标");
    }

    // === THROUGHPUT 测试 ===

    @Test
    @DisplayName("THROUGHPUT: 每秒请求数计算正确")
    void testThroughputCalculation() {
        Counter requestCounter = Counter.builder("nexus.payments.total")
                .register(meterRegistry);
        // 3600 次请求，窗口 60 分钟 = 3600 秒 → 1 req/s
        requestCounter.increment(3600);

        SlaTarget target = createTarget("Payment Throughput", "nexus.payments.total",
                0.5, SlaTarget.TargetType.THROUGHPUT, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(1.0, measurement.getMeasuredValue(), 0.01,
                "吞吐量应为 1 req/s（3600次 / 3600秒）");
        assertTrue(measurement.isMet(), "1.0 >= 0.5 目标，应达标");
    }

    @Test
    @DisplayName("THROUGHPUT: 无请求数据时返回 0")
    void testThroughputNoData() {
        SlaTarget target = createTarget("Payment Throughput", "nexus.payments.total",
                0.5, SlaTarget.TargetType.THROUGHPUT, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(0.0, measurement.getMeasuredValue(), 0.01);
        assertFalse(measurement.isMet(), "0 < 0.5 目标，应未达标");
    }

    // === ERROR_RATE 测试 ===

    @Test
    @DisplayName("ERROR_RATE: 错误率计算正确（使用 .total 计数器）")
    void testErrorRateWithTotalCounter() {
        Counter errorCounter = Counter.builder("nexus.payments.failed")
                .register(meterRegistry);
        Counter totalCounter = Counter.builder("nexus.payments.total")
                .register(meterRegistry);
        errorCounter.increment(5);
        totalCounter.increment(100);

        SlaTarget target = createTarget("Payment Error Rate", "nexus.payments",
                1.0, SlaTarget.TargetType.ERROR_RATE, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(5.0, measurement.getMeasuredValue(), 0.01,
                "错误率应为 5%（5次错误 / 100次总计）");
        assertFalse(measurement.isMet(), "5% > 1% 目标，应未达标");
    }

    @Test
    @DisplayName("ERROR_RATE: 无 .total 计数器时使用 confirmed + failed 计算")
    void testErrorRateWithoutTotalCounter() {
        Counter successCounter = Counter.builder("nexus.payments.confirmed")
                .register(meterRegistry);
        Counter errorCounter = Counter.builder("nexus.payments.failed")
                .register(meterRegistry);
        successCounter.increment(95);
        errorCounter.increment(5);

        SlaTarget target = createTarget("Payment Error Rate", "nexus.payments",
                1.0, SlaTarget.TargetType.ERROR_RATE, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(5.0, measurement.getMeasuredValue(), 0.01,
                "错误率应为 5%（5次错误 / 100次总计）");
        assertFalse(measurement.isMet());
    }

    @Test
    @DisplayName("ERROR_RATE: 无请求数据时返回 0")
    void testErrorRateNoData() {
        SlaTarget target = createTarget("Payment Error Rate", "nexus.payments",
                1.0, SlaTarget.TargetType.ERROR_RATE, 60);

        SlaMeasurement measurement = slaMonitorService.measure(target);

        assertEquals(0.0, measurement.getMeasuredValue(), 0.01);
        assertTrue(measurement.isMet(), "0% <= 1% 目标，应达标");
    }

    // === monitor() 定时任务测试 ===

    @Test
    @DisplayName("monitor(): 无已启用目标时跳过处理")
    void testMonitorNoEnabledTargets() {
        when(targetRepository.findByEnabledTrue()).thenReturn(List.of());

        slaMonitorService.monitor();

        verify(measurementRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("monitor(): 达标时不发布 SlaBreachEvent")
    void testMonitorMetTargetNoBreachEvent() {
        Counter successCounter = Counter.builder("nexus.payments.confirmed")
                .register(meterRegistry);
        successCounter.increment(100);

        SlaTarget target = createTarget("Availability", "nexus.payments",
                99.0, SlaTarget.TargetType.AVAILABILITY, 60);

        when(targetRepository.findByEnabledTrue()).thenReturn(List.of(target));

        slaMonitorService.monitor();

        verify(measurementRepository).save(any(SlaMeasurement.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("monitor(): 未达标时发布 SlaBreachEvent")
    void testMonitorBreachedTargetPublishesEvent() {
        Counter successCounter = Counter.builder("nexus.payments.confirmed")
                .register(meterRegistry);
        Counter failureCounter = Counter.builder("nexus.payments.failed")
                .register(meterRegistry);
        successCounter.increment(50);
        failureCounter.increment(50); // 50% 可用性，低于 99.9% 目标

        SlaTarget target = createTarget("Availability", "nexus.payments",
                99.9, SlaTarget.TargetType.AVAILABILITY, 60);

        when(targetRepository.findByEnabledTrue()).thenReturn(List.of(target));

        slaMonitorService.monitor();

        ArgumentCaptor<SlaMeasurement> measurementCaptor =
                ArgumentCaptor.forClass(SlaMeasurement.class);
        verify(measurementRepository).save(measurementCaptor.capture());
        SlaMeasurement saved = measurementCaptor.getValue();
        assertFalse(saved.isMet(), "50% < 99.9%，应未达标");

        verify(eventPublisher).publishEvent(any(SlaBreachEvent.class));
    }

    @Test
    @DisplayName("monitor(): 测量异常时不影响其他目标处理")
    void testMonitorExceptionDoesNotAffectOthers() {
        // 第一个目标使用不存在的 Timer，不会抛异常但返回 0
        SlaTarget target1 = createTarget("Latency", "nonexistent.timer",
                5000, SlaTarget.TargetType.LATENCY, 60);
        // 第二个目标正常
        Counter successCounter = Counter.builder("nexus.payments.confirmed")
                .register(meterRegistry);
        successCounter.increment(100);
        SlaTarget target2 = createTarget("Availability", "nexus.payments",
                99.0, SlaTarget.TargetType.AVAILABILITY, 60);

        when(targetRepository.findByEnabledTrue()).thenReturn(List.of(target1, target2));

        slaMonitorService.monitor();

        // 两个目标都应被处理（即使第一个可能未达标）
        verify(measurementRepository, times(2)).save(any(SlaMeasurement.class));
    }

    // === 辅助方法 ===

    private SlaTarget createTarget(String name, String metricName, double targetValue,
                                    SlaTarget.TargetType targetType, int windowMinutes) {
        SlaTarget target = new SlaTarget();
        target.setId(1L);
        target.setName(name);
        target.setMetricName(metricName);
        target.setTargetValue(targetValue);
        target.setTargetType(targetType);
        target.setWindowMinutes(windowMinutes);
        target.setEnabled(true);
        return target;
    }
}