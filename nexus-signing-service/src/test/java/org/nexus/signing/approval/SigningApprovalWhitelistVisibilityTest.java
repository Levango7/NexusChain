package org.nexus.signing.approval;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 审批人白名单的启动期可见性（协调板 2026-10-24 建议 1）。
 *
 * <p>白名单未配置是 fail-closed：所有决策被拒、大额签名的人工审批实际不可用。
 * 因此部署后必须有一条独立 WARN，而不是把 403 误读成权限配错。</p>
 *
 * <p>这些断言同时钉住"检查放在 {@code @PostConstruct}"：{@code approverWhitelist}
 * 是 {@code @Value} 字段注入，构造器执行时尚未赋值。</p>
 */
class SigningApprovalWhitelistVisibilityTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    @BeforeEach
    void attachAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(SigningApprovalService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        serviceLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("白名单未配置 → 独立 WARN 写明后果（大额签名人工审批实际不可用）")
    void unconfiguredWhitelistEmitsDedicatedWarn() {
        SigningApprovalService service = serviceWithWhitelist(null);

        service.logApproverWhitelistVisibility();

        assertTrue(messages(Level.WARN).anyMatch(m -> m.contains("审批人白名单未配置")
                        && m.contains("所有 approve/reject 决策都会被拒绝")
                        && m.contains("大额签名人工审批实际不可用")),
                "缺配置必须是独立 WARN 且写清后果，不能混在初始化 INFO 里");
    }

    @Test
    @DisplayName("空白白名单等同于未配置 → 同样 WARN")
    void blankWhitelistEmitsDedicatedWarn() {
        SigningApprovalService service = serviceWithWhitelist("   ");

        service.logApproverWhitelistVisibility();

        assertTrue(messages(Level.WARN).anyMatch(m -> m.contains("审批人白名单未配置")));
    }

    @Test
    @DisplayName("白名单已配置 → INFO 只报项数，不回显审批人地址")
    void configuredWhitelistLogsCountWithoutEchoingAddresses() {
        SigningApprovalService service = serviceWithWhitelist("alice@nexus, bob@nexus");

        service.logApproverWhitelistVisibility();

        String info = messages(Level.INFO)
                .filter(m -> m.contains("审批人白名单已配置"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("已配置时应有一条 INFO 确认可用"));
        assertTrue(info.contains("2 项"), "应报告有效项数（逗号后空格要 trim）");
        assertFalse(info.contains("alice@nexus"), "日志不应回显审批人地址");
        assertTrue(messages(Level.WARN).findAny().isEmpty(), "已配置时不能打未配置 WARN");
    }

    private static SigningApprovalService serviceWithWhitelist(String whitelist) {
        SigningApprovalService service = new SigningApprovalService(
                mock(MpcApprovalPolicy.class),
                mock(AuditLogService.class),
                new MapApprovalStore(new ConcurrentHashMap<>()),
                null);
        ReflectionTestUtils.setField(service, "approverWhitelist", whitelist);
        return service;
    }

    private Stream<String> messages(Level level) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage);
    }
}
