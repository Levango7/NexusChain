package org.nexus.gateway.security.threeds;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.audit.SecurityAuditService;
import org.nexus.gateway.security.audit.SecurityMetricsRecorder;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 3DS 认证完成事件"占位观察者"的可见性测试（#34，沿用 #98 的口径：只让空转可见，不改风控/资金语义）。
 *
 * <p>钉住四件事：</p>
 * <ol>
 *   <li><b>真接线</b>：在真 Spring 上下文里 publish，靠 {@code @EventListener} 注解解析送达 ——
 *       注解写错、类漏注册成 bean，都必须转红（手工调 handler 做不到这点）；</li>
 *   <li><b>计数落地</b>：事件确实把 {@code nexus_3ds_auth_total} 从"注册了但恒 0"里救出来；</li>
 *   <li><b>不刷屏</b>：认证量 = 事件量，逐条 WARN 是噪声 ⇒ N 条事件只出 1 条带标记的 WARN，其余留 INFO；</li>
 *   <li><b>不越权</b>：除 3DS 计数外其它安全计数器纹丝不动（观察者不得顺手发明风控动作）。</li>
 * </ol>
 */
class ThreeDsAuthCompletedObserverVisibilityTest {

    /** 告警标记的线上契约（写成字面量而非常量引用：只改测试的提交也能编译，红点落在运行期断言）。 */
    private static final String MARKER = "THREE_DS_AUTH_NO_RISK_CONSUMER";

    /** 与被观察者一致的指标名，同样是字面量契约。 */
    private static final String AUTH_COUNTER = "nexus_3ds_auth_total";

    /** 在真上下文里向观察者投递事件的能力（由 withContext 提供）。 */
    @FunctionalInterface
    private interface Emit {
        void publish(ThreeDsAuthCompletedEvent event);
    }

    private SimpleMeterRegistry registry;
    private SecurityMetricsRecorder recorder;
    /** 只验"每笔事件都递交给审计接缝"；映射与截断由 SecurityAuditPersistenceTest 专测。 */
    private SecurityAuditService securityAuditService;
    private Logger observerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new SecurityMetricsRecorder(registry);
        securityAuditService = mock(SecurityAuditService.class);

        observerLogger = (Logger) LoggerFactory.getLogger(ThreeDsAuthCompletedObserver.class);
        originalLevel = observerLogger.getLevel();
        observerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        observerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        observerLogger.detachAppender(appender);
        observerLogger.setLevel(originalLevel);
    }

    // ==================== 真接线 + 计数 ====================

    @Test
    @DisplayName("真上下文 publish：@EventListener 接得住，计数 +1，并产出带标记的 WARN")
    void publishedEventReachesObserverThroughSpringAnnotationProcessing() {
        withContext(emit -> {
            emit.publish(event(1L, TransStatus.N, AuthStatus.TIMEOUT));

            assertEquals(1.0, counterValue(AUTH_COUNTER),
                    "事件必须计入 " + AUTH_COUNTER + "（该计数器此前注册后无人喂，恒 0）");
            assertEquals(1, markerWarnings(), "首条事件必须产出 1 条带 " + MARKER + " 的 WARN");
        });
    }

    // ==================== 聚合，不刷屏 ====================

    @Test
    @DisplayName("N 条事件只出 1 条标记 WARN，其余逐条 INFO（认证量级不刷屏）")
    void alarmIsEmittedOncePerJvmButEveryEventStaysVisible() {
        withContext(emit -> {
            for (int i = 1; i <= 5; i++) {
                emit.publish(event((long) i, TransStatus.Y, AuthStatus.COMPLETED));
            }

            assertEquals(5.0, counterValue(AUTH_COUNTER), "每条事件都要计数");
            assertEquals(1, markerWarnings(), "标记 WARN 每 JVM 只 1 条，不能按交易量刷屏");
            long perEventInfo = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .filter(e -> e.getFormattedMessage().contains("已计数"))
                    .count();
            assertEquals(4, perEventInfo, "其余 4 条仍要逐条留 INFO（可见性不能只靠一条告警）");
        });
    }

    @Test
    @DisplayName("每笔事件都要落一条可查审计（与「只告警一次」是两件事）")
    void everyEventProducesOneAuditRow() {
        withContext(emit -> {
            for (int i = 1; i <= 3; i++) {
                emit.publish(event((long) i, TransStatus.N, AuthStatus.TIMEOUT));
            }
            verify(securityAuditService, times(3))
                    .recordThreeDsAuth(eq(100L), eq("N"), any(), contains("authStatus=TIMEOUT"));
        });
    }

    @Test
    @DisplayName("没有事件就没有审计行（不凭空造记录）")
    void noEventMeansNoAuditRow() {
        withContext(emit -> verify(securityAuditService, never())
                .recordThreeDsAuth(any(), any(), any(), any()));
    }

    // ==================== 不越权 ====

    @Test
    @DisplayName("观察者只动 3DS 计数：其它安全计数器纹丝不动（没顺手发明风控动作）")
    void observerTakesNoActionBeyondTheThreeDsCounter() {
        withContext(emit -> {
            emit.publish(event(7L, TransStatus.R, AuthStatus.COMPLETED));

            assertEquals(1.0, counterValue(AUTH_COUNTER));
            assertEquals(0.0, counterValue("nexus_replay_interceptions_total"));
            assertEquals(0.0, counterValue("nexus_password_locks_total"));
            assertEquals(0.0, counterValue("nexus_password_validations_total"));
            assertEquals(0.0, counterValue("nexus_encryption_operations_total"));
        });
    }

    // ==================== 可定位 ====================

    @Test
    @DisplayName("告警行带齐定位字段（订单号/商户/租户/两个状态/风险分）")
    void alarmCarriesFieldsNeededForTriage() {
        withContext(emit -> {
            emit.publish(new ThreeDsAuthCompletedEvent(this, 9001L, 55L, "tenant-77",
                    TransStatus.N, AuthStatus.TIMEOUT, 42, false, true));

            ILoggingEvent alarm = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getFormattedMessage().contains(MARKER))
                    .findFirst()
                    .orElse(null);
            assertNotNull(alarm, "必须有一条带标记的 WARN");
            String msg = alarm.getFormattedMessage();
            for (String field : new String[]{"9001", "55", "tenant-77", "N", "TIMEOUT", "42"}) {
                assertTrue(msg.contains(field), "告警缺少字段值 " + field + "，实际=" + msg);
            }
        });
    }

    // ==================== 辅助 ====================

    /**
     * 在真的 {@link AnnotationConfigApplicationContext} 里跑：注册两个 bean → refresh → publish。
     * 这样 {@code @EventListener} 由 Spring 的 EventListenerMethodProcessor 解析；
     * 注解被删/写错 ⇒ 计数恒 0，第一个用例即红。
     */
    private void withContext(java.util.function.Consumer<Emit> body) {
        ThreeDsAuthCompletedObserver observer =
                new ThreeDsAuthCompletedObserver(recorder, securityAuditService);
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(SecurityMetricsRecorder.class, () -> recorder);
            ctx.registerBean(SecurityAuditService.class, () -> securityAuditService);
            ctx.registerBean(ThreeDsAuthCompletedObserver.class, () -> observer);
            ctx.refresh();
            body.accept(event -> ctx.publishEvent(event));
        }
    }

    private double counterValue(String name) {
        Counter counter = registry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private int markerWarnings() {
        return (int) appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(MARKER))
                .count();
    }

    private ThreeDsAuthCompletedEvent event(Long orderId, TransStatus transStatus, AuthStatus authStatus) {
        return new ThreeDsAuthCompletedEvent(this, orderId, 100L, "tenant-1",
                transStatus, authStatus, 10, true, false);
    }
}
