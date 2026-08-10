package org.nexus.bridge.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultCircuitBreaker} 单元测试：覆盖熔断触发、重置、查询与事件发布。
 */
@ExtendWith(MockitoExtension.class)
class DefaultCircuitBreakerTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DefaultCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new DefaultCircuitBreaker();
    }

    @Test
    @DisplayName("初始状态: 未熔断")
    void initial_notTripped() {
        assertFalse(breaker.isTripped());
        assertNull(breaker.getTripReason());
    }

    @Test
    @DisplayName("trip: 设置熔断状态与原因")
    void trip_setsTrippedState() {
        breaker.trip("failure rate exceeded");
        assertTrue(breaker.isTripped());
        assertEquals("failure rate exceeded", breaker.getTripReason());
    }

    @Test
    @DisplayName("trip: 重复调用仅更新原因")
    void trip_repeatedUpdatesReason() {
        breaker.trip("reason-1");
        assertEquals("reason-1", breaker.getTripReason());

        breaker.trip("reason-2");
        assertTrue(breaker.isTripped());
        assertEquals("reason-2", breaker.getTripReason());
    }

    @Test
    @DisplayName("reset: 清除熔断状态")
    void reset_clearsTrippedState() {
        breaker.trip("reason");
        assertTrue(breaker.isTripped());

        breaker.reset();
        assertFalse(breaker.isTripped());
        assertNull(breaker.getTripReason());
    }

    @Test
    @DisplayName("reset: 未熔断时调用为空操作")
    void reset_whenNotTripped_isNoop() {
        breaker.reset();
        assertFalse(breaker.isTripped());
        assertNull(breaker.getTripReason());
    }

    @Test
    @DisplayName("trip -> reset -> trip 循环正确")
    void tripResetTrip_cycle() {
        breaker.trip("first");
        assertTrue(breaker.isTripped());

        breaker.reset();
        assertFalse(breaker.isTripped());

        breaker.trip("second");
        assertTrue(breaker.isTripped());
        assertEquals("second", breaker.getTripReason());
    }

    @Test
    @DisplayName("trip: null reason 时 isTripped 返回 false（null 表示未熔断）")
    void trip_nullReason() {
        breaker.trip(null);
        // tripReason 为 null 时 isTripped() 返回 false
        assertFalse(breaker.isTripped());
        assertNull(breaker.getTripReason());
    }

    @Test
    @DisplayName("有 eventPublisher 时发布事件")
    void trip_withEventPublisher_publishesEvent() {
        // 通过反射注入 eventPublisher
        try {
            java.lang.reflect.Field field = DefaultCircuitBreaker.class.getDeclaredField("eventPublisher");
            field.setAccessible(true);
            field.set(breaker, eventPublisher);
        } catch (Exception e) {
            fail("Failed to inject eventPublisher", e);
        }

        breaker.trip("test reason");

        verify(eventPublisher).publishEvent(any(CircuitBreakerTrippedEvent.class));
    }

    @Test
    @DisplayName("有 eventPublisher 但发布异常时不影响熔断状态")
    void trip_eventPublisherException_doesNotAffectState() {
        try {
            java.lang.reflect.Field field = DefaultCircuitBreaker.class.getDeclaredField("eventPublisher");
            field.setAccessible(true);
            field.set(breaker, eventPublisher);
        } catch (Exception e) {
            fail("Failed to inject eventPublisher", e);
        }

        doThrow(new RuntimeException("publish failed"))
                .when(eventPublisher).publishEvent(any(CircuitBreakerTrippedEvent.class));

        breaker.trip("test reason");

        assertTrue(breaker.isTripped());
        assertEquals("test reason", breaker.getTripReason());
    }

    @Test
    @DisplayName("重复 trip 不重复发布事件")
    void trip_repeatedDoesNotRepublishEvent() {
        try {
            java.lang.reflect.Field field = DefaultCircuitBreaker.class.getDeclaredField("eventPublisher");
            field.setAccessible(true);
            field.set(breaker, eventPublisher);
        } catch (Exception e) {
            fail("Failed to inject eventPublisher", e);
        }

        breaker.trip("first");
        breaker.trip("second");

        verify(eventPublisher, times(1)).publishEvent(any(CircuitBreakerTrippedEvent.class));
    }
}