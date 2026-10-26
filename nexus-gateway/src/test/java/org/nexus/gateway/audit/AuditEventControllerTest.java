package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计查询端点门禁（P0 修复 2026-10-11；商户隔离 2026-10-26）。
 *
 * <p>核心约束：<b>只读</b>（审计表 append-only，绝不能有改/删入口）
 * + 查询走仓储 + <b>四个视图一律按调用方商户隔离</b>。</p>
 *
 * <p>修复前的行为：类级注解放行 MERCHANT，而四个端点都不校验归属 ——
 * {@code /merchant/{id}} 可读任意商户，另外三个视图返回全平台数据。
 * 本类把「查他人 403」「无上下文 403」「按动作/时间窗/分页均按调用方过滤」钉死。</p>
 */
class AuditEventControllerTest {

    private static final Long CALLER = 700L;

    private AuditEventRepository repository;
    private AuditEventController controller;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        controller = new AuditEventController(repository, new MerchantOwnershipGuard());
    }

    /** 模拟 ApiKeyInterceptor 注入的商户上下文；传 null 表示未认证。 */
    private MockHttpServletRequest asMerchant(Long merchantId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (merchantId != null) {
            req.setAttribute(MerchantOwnershipGuard.MERCHANT_ID_ATTR, merchantId);
        }
        return req;
    }

    private AuditEventEntity event(String action, Long merchantId, Instant at) {
        AuditEventEntity e = new AuditEventEntity();
        e.setAction(action);
        e.setMerchantId(merchantId);
        e.setEventTime(at);
        return e;
    }

    // ==================== 按商户 ====================

    @Test
    @DisplayName("按商户查：查自己 → 走仓储倒序查询")
    void byMerchant_ownMerchant_usesRepository() {
        when(repository.findByMerchantIdOrderByEventTimeDesc(CALLER))
                .thenReturn(List.of(event("REFUND_EXECUTED", CALLER, Instant.now())));

        ResponseEntity<List<AuditEventEntity>> resp =
                controller.byMerchant(CALLER, asMerchant(CALLER));

        assertEquals(1, resp.getBody().size());
        assertEquals("REFUND_EXECUTED", resp.getBody().get(0).getAction());
        verify(repository).findByMerchantIdOrderByEventTimeDesc(CALLER);
    }

    @Test
    @DisplayName("按商户查：查他人 → 403（修复前可读任意商户）")
    void byMerchant_otherMerchant_denied() {
        assertThrows(MerchantOwnershipException.class,
                () -> controller.byMerchant(999L, asMerchant(CALLER)));

        // 关键：拒绝必须发生在查询之前，不能"查出来再判断"
        verify(repository, never()).findByMerchantIdOrderByEventTimeDesc(any());
    }

    @Test
    @DisplayName("按商户查：无商户上下文 → 403")
    void byMerchant_noContext_denied() {
        assertThrows(MerchantOwnershipException.class,
                () -> controller.byMerchant(CALLER, asMerchant(null)));
        verify(repository, never()).findByMerchantIdOrderByEventTimeDesc(any());
    }

    // ==================== 按动作 ====================

    @Test
    @DisplayName("按动作查：限定当前商户（修复前返回全平台）")
    void byAction_scopedToCaller() {
        when(repository.findByMerchantIdAndActionOrderByEventTimeDesc(
                CALLER, "CHANNEL_REFUND_EXECUTED"))
                .thenReturn(List.of(event("CHANNEL_REFUND_EXECUTED", CALLER, Instant.now())));

        assertEquals(1, controller.byAction("CHANNEL_REFUND_EXECUTED", asMerchant(CALLER))
                .getBody().size());
        verify(repository).findByMerchantIdAndActionOrderByEventTimeDesc(
                CALLER, "CHANNEL_REFUND_EXECUTED");
    }

    @Test
    @DisplayName("按动作查：无商户上下文 → 403")
    void byAction_noContext_denied() {
        assertThrows(MerchantOwnershipException.class,
                () -> controller.byAction("REFUND_EXECUTED", asMerchant(null)));
    }

    // ==================== 按时间窗 ====================

    @Test
    @DisplayName("时间窗：先鉴权 —— 无上下文时即使参数非法也报 403（不泄露参数规则）")
    void byWindow_noContext_deniedBeforeValidation() {
        Instant now = Instant.now();
        assertThrows(MerchantOwnershipException.class,
                () -> controller.byWindow(now, now.minus(1, ChronoUnit.HOURS), asMerchant(null)));
    }

    @Test
    @DisplayName("时间窗校验：from 不早于 to → 参数错")
    void byWindow_rejectsInvalidRange() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> controller.byWindow(now, now.minus(1, ChronoUnit.HOURS), asMerchant(CALLER)));
        verify(repository, never())
                .findByMerchantIdAndEventTimeBetweenOrderByEventTimeDesc(any(), any(), any());
    }

    @Test
    @DisplayName("时间窗合法：限定当前商户（修复前返回全平台）")
    void byWindow_scopedToCaller() {
        Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(repository.findByMerchantIdAndEventTimeBetweenOrderByEventTimeDesc(CALLER, from, to))
                .thenReturn(List.of(event("REFUND_APPROVED", CALLER, from)));

        assertEquals(1, controller.byWindow(from, to, asMerchant(CALLER)).getBody().size());
    }

    // ==================== 分页兜底 ====================

    @Test
    @DisplayName("分页：size 越界夹到 1..200，且限定当前商户")
    void recent_clampsPageSize() {
        when(repository.findByMerchantId(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<java.util.Map<String, Object>> resp =
                controller.recent(0, 10000, asMerchant(CALLER));

        assertEquals(0L, resp.getBody().get("total"));
        assertEquals(200, resp.getBody().get("limit"));

        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByMerchantId(eq(CALLER), c.capture());
        assertEquals(200, c.getValue().getPageSize(), "size=10000 应被夹到 200");
    }

    @Test
    @DisplayName("分页：page 负数夹到 0")
    void recent_clampsNegativePage() {
        when(repository.findByMerchantId(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        controller.recent(-5, 10, asMerchant(CALLER));

        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByMerchantId(eq(CALLER), c.capture());
        assertEquals(0, c.getValue().getPageNumber());
    }

    @Test
    @DisplayName("分页：无商户上下文 → 403")
    void recent_noContext_denied() {
        assertThrows(MerchantOwnershipException.class,
                () -> controller.recent(0, 10, asMerchant(null)));
    }
}
