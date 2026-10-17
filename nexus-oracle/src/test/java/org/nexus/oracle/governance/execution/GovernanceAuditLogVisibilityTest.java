package org.nexus.oracle.governance.execution;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 治理审计"到底有没有持久化"这件事的可见性测试。
 *
 * <p>钉住三件事：</p>
 * <ol>
 *   <li>仓储缺席（今天的生产现实）时必须发出带 {@code GOVERNANCE_AUDIT_MEMORY_ONLY} 标记的 WARN，
 *       让 GOV-P0-02 那句"重启不丢失"的假宣称在日志里可查，而不是只活在注释里。</li>
 *   <li>仓储存在时不该再报内存告警。</li>
 *   <li><b>装配本身</b>：本类有两个 public 构造器。Spring 在没有构造器标 {@code @Autowired} 时会静默挑
 *       无参那个 ⇒ 持久化腿永不生效而所有直接 {@code new} 的单测照绿。这里用真容器双向验证
 *       "仓储在场→选中带仓储的构造器""仓储缺席→退回无参"，把该坑钉成回归用例。</li>
 * </ol>
 */
class GovernanceAuditLogVisibilityTest {

    private static final String MARKER = "GOVERNANCE_AUDIT_MEMORY_ONLY";

    private Logger auditLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        auditLogger = (Logger) LoggerFactory.getLogger(GovernanceAuditLog.class);
        originalLevel = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        auditLogger.detachAppender(appender);
        auditLogger.setLevel(originalLevel);
    }

    // ==================== 告警语义 ====================

    @Test
    @DisplayName("仓储缺席＝只有内存层：必须发带稳定标记的 WARN")
    void memoryOnlyModeWarnsWithStableMarker() {
        GovernanceAuditLog audit = new GovernanceAuditLog();

        audit.announcePersistenceMode();

        List<ILoggingEvent> warns = events(Level.WARN);
        assertEquals(1, warns.size(), "内存态应且只应发一条 WARN");
        assertTrue(warns.get(0).getFormattedMessage().contains(MARKER),
                "WARN 必须带 " + MARKER);
    }

    @Test
    @DisplayName("仓储在场：不再报内存告警，改记 info")
    void persistentModeDoesNotWarn() {
        GovernanceAuditLog audit =
                new GovernanceAuditLog(mock(GovernanceAuditLogRepository.class), new ObjectMapper());

        audit.announcePersistenceMode();

        assertFalse(hasWarnWithMarker(), "持久化已接上时不许偷用内存告警标记");
        assertTrue(events(Level.INFO).stream()
                .anyMatch(e -> e.getFormattedMessage().contains("持久化已启用")));
    }

    // ==================== 装配本身（真容器，双向） ====================

    @Test
    @DisplayName("真容器里仓储缺席 → 退回无参构造器并告警（今天的 prod 现实）")
    void springFallsBackToNoArgCtorWhenRepositoryAbsent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(NoRepositoryConfig.class);
            // 注册类本身，让 Spring 走构造器选择逻辑；用 @Bean 手工 new 会绕过它、等于没测
            ctx.register(GovernanceAuditLog.class);
            ctx.refresh();

            GovernanceAuditLog audit = ctx.getBean(GovernanceAuditLog.class);

            assertNotNull(audit);
            assertTrue(hasWarnWithMarker(),
                    "容器装配出来的 bean 也必须把内存态说出来（这条正是防 @Autowired 漏标的哨兵）");
        }
    }

    @Test
    @DisplayName("真容器里仓储在场 → 选中带仓储的构造器且不告警")
    void springSelectsRepositoryCtorWhenRepositoryPresent() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(WithRepositoryConfig.class);
            ctx.register(GovernanceAuditLog.class);
            ctx.refresh();

            GovernanceAuditLog audit = ctx.getBean(GovernanceAuditLog.class);

            assertNotNull(audit);
            assertFalse(hasWarnWithMarker(),
                    "仓储 bean 在场却仍报内存告警 ⇒ 说明带仓储的构造器没被选中（@Autowired 标记失效）");
        }
    }

    @Test
    @DisplayName("结构断言：两个 public 构造器里恰有一个标了 @Autowired(required=false)")
    void exactlyOneConstructorIsAutowiredMarked() {
        long marked = java.util.Arrays.stream(GovernanceAuditLog.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(
                        org.springframework.beans.factory.annotation.Autowired.class))
                .count();

        assertEquals(1, marked,
                "多构造器 bean 必须有且只有一个构造器标 @Autowired，否则 Spring 静默挑无参那个");
        assertEquals(2, GovernanceAuditLog.class.getConstructors().length,
                "构造器数量变了就要重审上面那条判据");
    }

    @Test
    @DisplayName("结构断言：启动播报挂在 @PostConstruct 上（否则真容器里永不执行）")
    void announceHookIsPostConstruct() throws NoSuchMethodException {
        assertNotNull(GovernanceAuditLog.class.getMethod("announcePersistenceMode")
                        .getAnnotation(PostConstruct.class),
                "没有 @PostConstruct，这条告警只会在单测里跑、生产里永不发");
    }

    // ==================== 辅助 ====================

    private List<ILoggingEvent> events(Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    private boolean hasWarnWithMarker() {
        return events(Level.WARN).stream().anyMatch(e -> e.getFormattedMessage().contains(MARKER));
    }

    @Configuration(proxyBeanMethods = false)
    static class NoRepositoryConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
        // 刻意不提供 GovernanceAuditLogRepository：复现今天的 prod 现实
        // （org.nexus.oracle 不在 @EnableJpaRepositories/@EntityScan 根内 ⇒ 仓储 bean 永不注册）
    }

    @Configuration(proxyBeanMethods = false)
    static class WithRepositoryConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        GovernanceAuditLogRepository governanceAuditLogRepository() {
            return mock(GovernanceAuditLogRepository.class);
        }
    }
}
