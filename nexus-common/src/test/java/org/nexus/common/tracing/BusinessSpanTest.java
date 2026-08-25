package org.nexus.common.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BusinessSpan} 单元测试（纯 JUnit 5 + Mockito，无 Spring 上下文）。
 *
 * <p>覆盖两条主路径：</p>
 * <ul>
 *   <li><b>no-op 降级路径</b>：{@code tracer == null} 时所有操作安全空实现；</li>
 *   <li><b>真实路径</b>：mock Micrometer {@link Tracer}/{@link Span} 接口，
 *       验证链式构建、tag 写入、error/success 标记、close 幂等与
 *       try-with-resources 自动结束。</li>
 * </ul>
 *
 * <p>Micrometer Tracing 1.2.5 的 {@code Span}/{@code Tracer} 均为接口，
 * 直接 Mockito mock 即可（仅依赖 mockito-core，手动 mock 不引入
 * mockito-junit-jupiter 扩展）；{@code tracer.nextSpan()} 返回 {@code Span}
 * （非 Builder），链式 {@code nextSpan().name(n).start()} 中
 * {@code name(String)} 与 {@code start()} 需 stub 返回 span 自身。</p>
 */
class BusinessSpanTest {

    private Tracer tracer;

    private Span span;

    @BeforeEach
    void setUpMocks() {
        tracer = Mockito.mock(Tracer.class);
        span = Mockito.mock(Span.class);
    }

    /** 为「真实路径」准备链式 stub：nextSpan().name(n).start() 返回同一个 mock span。 */
    private BusinessSpan startRealSpan(String name) {
        when(tracer.nextSpan()).thenReturn(span);
        when(span.name(name)).thenReturn(span);
        when(span.start()).thenReturn(span);
        return BusinessSpan.start(tracer, name);
    }

    // ==================== no-op 降级路径 ====================

    @Nested
    @DisplayName("no-op 降级路径（tracer == null）")
    class NoOpDegradation {

        @Test
        @DisplayName("start(null, \"x\") 返回实例且 name 正确、rawSpan 为 null")
        void startNullTracerReturnsInstanceWithNameAndNoRawSpan() {
            BusinessSpan bs = BusinessSpan.start(null, "noop.span");

            assertNotNull(bs, "tracer 为 null 时仍应返回可用实例");
            assertEquals("noop.span", bs.name(), "name() 应返回传入的 span 名称");
            assertNull(bs.rawSpan(), "no-op 模式下 rawSpan 应为 null");
        }

        @Test
        @DisplayName("no-op 下全部操作（attr×5/error×2/success/close×2）不抛异常")
        void allOperationsAreSafeNoOpsWithoutTracer() {
            BusinessSpan bs = BusinessSpan.start(null, "noop.span");

            assertDoesNotThrow(() -> bs
                            .attr("str.key", "value")
                            .attr("long.key", 42L)
                            .attr("double.key", 1.5d)
                            .attr("bool.key", true)
                            .attr("obj.key", new Object())
                            .error(null)
                            .error(new RuntimeException("boom"))
                            .success(),
                    "no-op 模式下链式属性/错误/成功标记不应抛异常");
            assertDoesNotThrow(bs::close, "首次 close 不应抛异常");
            assertDoesNotThrow(bs::close, "重复 close 不应抛异常");
            assertNull(bs.rawSpan(), "全程不应产生底层 span");
        }

        @Test
        @DisplayName("start(null, null) 抛 NullPointerException")
        void startNullNameThrowsNpe() {
            assertThrows(NullPointerException.class,
                    () -> BusinessSpan.start(null, null),
                    "span name 为 null 必须快速失败");
        }
    }

    // ==================== mock Tracer/Span 真实路径 ====================

    @Nested
    @DisplayName("真实路径（mock Tracer + Span）")
    class RealTracerPath {

        @Test
        @DisplayName("start 走 nextSpan().name(\"payment.create\").start() 链式并持有 rawSpan")
        void startBuildsSpanThroughChainedCalls() {
            when(tracer.nextSpan()).thenReturn(span);
            when(span.name("payment.create")).thenReturn(span);
            when(span.start()).thenReturn(span);

            BusinessSpan bs = BusinessSpan.start(tracer, "payment.create");

            verify(tracer).nextSpan();
            verify(span).name("payment.create");
            verify(span).start();
            assertNotNull(bs.rawSpan(), "应持有已启动的底层 span");
            assertSame(span, bs.rawSpan(), "rawSpan 应即链式构建产物");
        }

        @Test
        @DisplayName("attr(String, String 非 null) 触发 span.tag(key, 字符串值)")
        void attrStringValueTagsSpan() {
            startRealSpan("span.attr.str").attr("payment.id", "pay_001");

            verify(span).tag("payment.id", "pay_001");
        }

        @Test
        @DisplayName("attr(long) 以字符串形式触发 span.tag")
        void attrLongValueTagsSpanWithStringOfLong() {
            startRealSpan("span.attr.long").attr("payment.amount.cents", 123456789L);

            verify(span).tag("payment.amount.cents", "123456789");
        }

        @Test
        @DisplayName("attr(double) 以字符串形式触发 span.tag")
        void attrDoubleValueTagsSpanWithStringOfDouble() {
            startRealSpan("span.attr.double").attr("payment.amount", 99.5d);

            verify(span).tag("payment.amount", "99.5");
        }

        @Test
        @DisplayName("attr(boolean) 以字符串形式触发 span.tag")
        void attrBooleanValueTagsSpanWithStringOfBoolean() {
            startRealSpan("span.attr.bool").attr("payment.retry", true);

            verify(span).tag("payment.retry", "true");
        }

        @Test
        @DisplayName("attr(Object 非 null) 以 toString 结果触发 span.tag")
        void attrObjectValueTagsSpanWithToString() {
            Object value = new Object() {
                @Override public String toString() {
                    return "obj-42";
                }
            };

            startRealSpan("span.attr.obj").attr("payment.payload", value);

            verify(span).tag("payment.payload", "obj-42");
        }

        @Test
        @DisplayName("attr(String, null) 跳过 tag")
        void attrNullStringSkipsTag() {
            startRealSpan("span.skip.str").attr("payment.id", (String) null);

            verify(span, never()).tag(anyString(), any());
        }

        @Test
        @DisplayName("attr(Object, null) 跳过 tag")
        void attrNullObjectSkipsTag() {
            startRealSpan("span.skip.obj").attr("payment.payload", (Object) null);

            verify(span, never()).tag(anyString(), any());
        }

        @Test
        @DisplayName("error(null) 仅打 tag(\"error\",\"true\")，不调用 span.error")
        void errorNullThrowableTagsErrorTrue() {
            startRealSpan("span.error.null").error(null);

            verify(span).tag("error", "true");
            verify(span, never()).error(any());
        }

        @Test
        @DisplayName("error(exception) 记录异常事件到 span")
        void errorThrowableRecordsExceptionOnSpan() {
            RuntimeException boom = new RuntimeException("boom");

            startRealSpan("span.error.ex").error(boom);

            verify(span).error(boom);
        }

        @Test
        @DisplayName("success() 打 tag(\"status\",\"ok\")")
        void successTagsStatusOk() {
            startRealSpan("span.success").success();

            verify(span).tag("status", "ok");
        }

        @Test
        @DisplayName("close 幂等：连续两次 close 只触发一次 span.end()")
        void closeIsIdempotentAndEndsSpanOnce() {
            BusinessSpan bs = startRealSpan("span.close.idempotent");

            bs.close();
            bs.close();

            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("try-with-resources 正常退出时自动 close 并 end 一次")
        void tryWithResourcesAutoClosesSpan() {
            AtomicInteger inFlight = new AtomicInteger();
            when(tracer.nextSpan()).thenReturn(span);
            when(span.name("twr.span")).thenReturn(span);
            when(span.start()).thenReturn(span);

            try (BusinessSpan bs = BusinessSpan.start(tracer, "twr.span")) {
                inFlight.incrementAndGet();
                bs.attr("twr.key", "twr.value");
            }

            assertEquals(1, inFlight.get(), "业务块应正常执行");
            verify(span).tag("twr.key", "twr.value");
            verify(span, times(1)).end();
        }

        @Test
        @DisplayName("error 后 close 仍正常 end（error 与 end 互不替代）")
        void closeAfterErrorStillEndsSpan() {
            RuntimeException boom = new RuntimeException("late-failure");
            BusinessSpan bs = startRealSpan("span.error.then.close");
            bs.error(boom);
            bs.close();

            verify(span).error(boom);
            verify(span, times(1)).end();
        }
    }

    // ==================== 参数化键值边界 ====================

    @Nested
    @DisplayName("混合校验")
    class MixedChecks {

        @Test
        @DisplayName("同一实例多次 attr 各重载互不干扰，全部写入对应 tag")
        void multipleAttrOverloadsAllTagIndependently() {
            startRealSpan("span.multi")
                    .attr("a.str", "v1")
                    .attr("a.long", 7L)
                    .attr("a.double", 0.25d)
                    .attr("a.bool", false)
                    .attr("a.obj", Integer.valueOf(9));

            verify(span).tag("a.str", "v1");
            verify(span).tag("a.long", "7");
            verify(span).tag("a.double", "0.25");
            verify(span).tag("a.bool", "false");
            verify(span).tag(eq("a.obj"), eq("9"));
            verify(span, times(5)).tag(anyString(), anyString());
        }
    }
}