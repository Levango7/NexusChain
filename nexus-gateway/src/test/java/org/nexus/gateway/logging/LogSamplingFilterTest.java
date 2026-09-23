package org.nexus.gateway.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link LogSamplingFilter} 单元测试（任务 #28）。
 *
 * <p>测试采样逻辑：ERROR/WARN 不采样、INFO/DEBUG 按采样率过滤、
 * 仅对配置的 logger 进行采样。</p>
 */
class LogSamplingFilterTest {

    private LogSamplingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LogSamplingFilter();
        filter.setSamplingRate(0.1); // 10% 采样率
        filter.setSamplerLoggers(List.of("org.springframework.web.filter"));
        filter.start();
    }

    // === ERROR/WARN 不采样 ===

    @Test
    @DisplayName("ERROR 级别始终输出（不采样）")
    void testErrorAlwaysPass() {
        ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.ERROR);
        assertEquals(FilterReply.NEUTRAL, filter.decide(event), "ERROR 应始终通过");
    }

    @Test
    @DisplayName("WARN 级别始终输出（不采样）")
    void testWarnAlwaysPass() {
        ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.WARN);
        assertEquals(FilterReply.NEUTRAL, filter.decide(event), "WARN 应始终通过");
    }

    // === 采样范围内的 logger ===

    @Test
    @DisplayName("采样范围内 logger 的 INFO 日志按采样率过滤")
    void testSamplingInRangeLogger() {
        // 采样率 0.1 → 每 10 条通过 1 条
        int passCount = 0;
        int total = 100;
        for (int i = 0; i < total; i++) {
            ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.INFO);
            if (filter.decide(event) == FilterReply.NEUTRAL) {
                passCount++;
            }
        }
        assertEquals(10, passCount, "10% 采样率应每 10 条通过 1 条，100 条中通过 10 条");
    }

    @Test
    @DisplayName("采样范围内 logger 的 DEBUG 日志按采样率过滤")
    void testSamplingDebugInRangeLogger() {
        int passCount = 0;
        int total = 100;
        for (int i = 0; i < total; i++) {
            ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.DEBUG);
            if (filter.decide(event) == FilterReply.NEUTRAL) {
                passCount++;
            }
        }
        assertEquals(10, passCount, "DEBUG 也应按采样率过滤");
    }

    // === 采样范围外的 logger ===

    @Test
    @DisplayName("采样范围外 logger 的 INFO 日志不采样（全部通过）")
    void testNoSamplingOutOfRangeLogger() {
        ILoggingEvent event = mockEvent("org.nexus.gateway.service.PaymentService", Level.INFO);
        assertEquals(FilterReply.NEUTRAL, filter.decide(event), "范围外 logger 不应采样");
    }

    @Test
    @DisplayName("采样范围外 logger 的 DEBUG 日志不采样（全部通过）")
    void testNoSamplingDebugOutOfRangeLogger() {
        ILoggingEvent event = mockEvent("org.nexus.gateway.service.PaymentService", Level.DEBUG);
        assertEquals(FilterReply.NEUTRAL, filter.decide(event), "范围外 logger 不应采样");
    }

    // === 采样率边界 ===

    @Test
    @DisplayName("采样率 1.0 时全部通过")
    void testSamplingRateOne() {
        filter.setSamplingRate(1.0);
        filter.start();
        for (int i = 0; i < 20; i++) {
            ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.INFO);
            assertEquals(FilterReply.NEUTRAL, filter.decide(event), "采样率 100% 时应全部通过");
        }
    }

    @Test
    @DisplayName("采样率 0.0 时全部拒绝")
    void testSamplingRateZero() {
        filter.setSamplingRate(0.0);
        filter.start();
        for (int i = 0; i < 20; i++) {
            ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.INFO);
            assertEquals(FilterReply.DENY, filter.decide(event), "采样率 0% 时应全部拒绝");
        }
    }

    @Test
    @DisplayName("采样率 0.5 时约一半通过")
    void testSamplingRateHalf() {
        filter.setSamplingRate(0.5);
        filter.start();
        int passCount = 0;
        int total = 100;
        for (int i = 0; i < total; i++) {
            ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.INFO);
            if (filter.decide(event) == FilterReply.NEUTRAL) {
                passCount++;
            }
        }
        assertEquals(50, passCount, "50% 采样率应约一半通过");
    }

    // === 前缀匹配 ===

    @Test
    @DisplayName("logger 名称前缀匹配")
    void testPrefixMatch() {
        // org.springframework.web.filter.xxx 也应匹配
        ILoggingEvent event = mockEvent("org.springframework.web.filter.DispatcherServlet", Level.DEBUG);
        // 应该被采样（不是全部通过）
        int passCount = 0;
        for (int i = 0; i < 100; i++) {
            ILoggingEvent e = mockEvent("org.springframework.web.filter.DispatcherServlet", Level.DEBUG);
            if (filter.decide(e) == FilterReply.NEUTRAL) {
                passCount++;
            }
        }
        assertEquals(10, passCount, "前缀匹配的 logger 也应被采样");
    }

    // === 未启动时 ===

    @Test
    @DisplayName("Filter 未启动时返回 NEUTRAL")
    void testNotStarted() {
        LogSamplingFilter unstarted = new LogSamplingFilter();
        // 不调用 start()
        ILoggingEvent event = mockEvent("org.springframework.web.filter.FilterA", Level.INFO);
        assertEquals(FilterReply.NEUTRAL, unstarted.decide(event), "未启动时应返回 NEUTRAL");
    }

    // === 辅助方法 ===

    private ILoggingEvent mockEvent(String loggerName, Level level) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getLevel()).thenReturn(level);
        return event;
    }
}