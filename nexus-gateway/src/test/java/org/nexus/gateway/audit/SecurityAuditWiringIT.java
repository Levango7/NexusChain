package org.nexus.gateway.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.audit.SecurityAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;

/**
 * 安全事件落审计库的<b>真装配</b>回归（#52 A 段）。
 *
 * <p>单测里我都是直接 {@code new SecurityAuditService(sink)}，所以"Spring 到底注入了哪个构造器"
 * 这件事单测永远测不到——而 {@code AuditLogService} 的注释记录过：本仓已在"多构造器 + 无
 * {@code @Autowired}"上栽过（Spring 会退回无参构造器，依赖静默不注入，表现为"日志照写、库里没行"，
 * 门禁与上下文启动都不会红）。本用例从容器里取真实的 {@link SecurityAuditService} bean 走一遍，
 * 只要主构造器漏标 {@code @Autowired}，{@code sink} 就是 null、仓储收不到调用 ⇒ 立刻红。</p>
 *
 * <p>跑在 {@code integrationTest} 任务（CI 阻断执行）：它本来就是为"整上下文装配"存在的。</p>
 */
@SpringBootTest
@ActiveProfiles("sandbox")
@Tag("integration")
@DisplayName("安全事件 → audit_events 的生产装配闭环")
class SecurityAuditWiringIT {

    @Autowired
    private SecurityAuditService securityAuditService;

    /** 只把最末端的仓储换成 mock：前面整条 bean 图（含 sink 注入）都走真装配。 */
    @MockitoBean
    private AuditEventRepository auditEventRepository;

    // sandbox 上下文缺 Tracer bean（同 MerchantAdminJwtAuthIT 的先例）
    @MockitoBean
    private io.micrometer.tracing.Tracer tracer;

    @Test
    @DisplayName("容器里的 SecurityAuditService 确实把事件交给仓储（漏 @Autowired 即红）")
    void securityEventReachesAuditRepositoryThroughWiredBeans() {
        securityAuditService.recordThreeDsAuth(4242L, "N", 9001L,
                "authStatus=TIMEOUT, riskScore=42, at=" + OffsetDateTime.now());

        org.mockito.ArgumentCaptor<AuditEventEntity> captor =
                org.mockito.ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(auditEventRepository).save(captor.capture());

        AuditEventEntity row = captor.getValue();
        assertEquals("SECURITY_THREE_DS_AUTH", row.getAction(),
                "类别要能按 action 检索（V96 上已有 idx_audit_action）");
        assertEquals("system", row.getActor(), "安全观测没有人在操作，actor 固定 system");
        assertEquals("paymentOrder:9001", row.getResource());
        assertEquals(4242L, row.getMerchantId().longValue());
        assertNotNull(row.getEventTime(), "event_time 非空是 V96 的列约束");
        org.junit.jupiter.api.Assertions.assertTrue(row.getDetail().contains("riskScore=42"), row.getDetail());
    }
}
