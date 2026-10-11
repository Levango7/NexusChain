package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 审计查询端点门禁（P0 修复 2026-10-11）。
 *
 * <p>核心约束：<b>只读</b>（审计表 append-only，绝不能有改/删入口）+ 查询走仓储。</p>
 */
class AuditEventControllerTest {

    private AuditEventRepository repository;
    private AuditEventController controller;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        controller = new AuditEventController(repository);
    }

    private AuditEventEntity event(String action, Long merchantId, Instant at) {
        AuditEventEntity e = new AuditEventEntity();
        e.setAction(action);
        e.setMerchantId(merchantId);
        e.setEventTime(at);
        return e;
    }

    @Test
    @DisplayName("按商户查：走仓储倒序查询")
    void byMerchant_usesRepository() {
        when(repository.findByMerchantIdOrderByEventTimeDesc(700L))
                .thenReturn(List.of(event("REFUND_EXECUTED", 700L, Instant.now())));

        ResponseEntity<List<AuditEventEntity>> resp = controller.byMerchant(700L);

        assertEquals(1, resp.getBody().size());
        assertEquals("REFUND_EXECUTED", resp.getBody().get(0).getAction());
        verify(repository).findByMerchantIdOrderByEventTimeDesc(700L);
    }

    @Test
    @DisplayName("按动作查：资金动作可按类别检索")
    void byAction_usesRepository() {
        when(repository.findByActionOrderByEventTimeDesc("CHANNEL_REFUND_EXECUTED"))
                .thenReturn(List.of(event("CHANNEL_REFUND_EXECUTED", 500L, Instant.now())));
        assertEquals(1, controller.byAction("CHANNEL_REFUND_EXECUTED").getBody().size());
    }

    @Test
    @DisplayName("时间窗校验：from 不早于 to → 400 参数错")
    void byWindow_rejectsInvalidRange() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class,
                () -> controller.byWindow(now, now.minus(1, ChronoUnit.HOURS)));
        verify(repository, never()).findByEventTimeBetween(any(), any());
    }

    @Test
    @DisplayName("时间窗合法：走仓储")
    void byWindow_happyPath() {
        Instant from = Instant.now().minus(2, ChronoUnit.DAYS);
        Instant to = Instant.now();
        when(repository.findByEventTimeBetween(from, to))
                .thenReturn(List.of(event("REFUND_APPROVED", 1L, from)));
        assertEquals(1, controller.byWindow(from, to).getBody().size());
    }

    @Test
    @DisplayName("分页兜底：size 越界被夹到 1..200（防误拉全表）")
    void recent_clampsPageSize() {
        when(repository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        ResponseEntity<java.util.Map<String, Object>> resp = controller.recent(0, 10000);
        assertEquals(0L, resp.getBody().get("total"));
        assertEquals(200, resp.getBody().get("limit"));
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(c.capture());
        assertEquals(200, c.getValue().getPageSize(), "size=10000 应被夹到 200");
    }

    @Test
    @DisplayName("分页兜底：page 负数被夹到 0")
    void recent_clampsNegativePage() {
        when(repository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        controller.recent(-5, 10);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(c.capture());
        assertEquals(0, c.getValue().getPageNumber());
    }
}
