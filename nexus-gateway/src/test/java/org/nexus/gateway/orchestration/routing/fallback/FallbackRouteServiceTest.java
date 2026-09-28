package org.nexus.gateway.orchestration.routing.fallback;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FallbackRouteService} 与 {@link CompensationRoutingService} 单元测试。
 */
class FallbackRouteServiceTest {

    private FallbackRouteConfigRepository repository;
    private FallbackRouteService service;

    @BeforeEach
    void setUp() {
        repository = mock(FallbackRouteConfigRepository.class);
        service = new FallbackRouteService(repository);
    }

    private FallbackRouteConfig config(Long merchantId, String primary, String csv, String conditions, int priority) {
        FallbackRouteConfig config = new FallbackRouteConfig();
        config.setId(1L);
        config.setMerchantId(merchantId);
        config.setPrimaryConnector(primary);
        config.setFallbackConnectorsCsv(csv);
        config.setConditionsJson(conditions);
        config.setPriority(priority);
        config.setEnabled(true);
        return config;
    }

    @Test
    @DisplayName("resolveFallbackChain: 商户级配置优先于全局级；CSV 去空去重去主渠道")
    void merchantLevelWins() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(
                config(null, "chain", " mock, chain ,wechat", null, 5),
                config(42L, "chain", "alipay,mock", null, 1)));

        List<String> chain = service.resolveFallbackChain(42L, "chain",
                BigDecimal.valueOf(100), "NEX");
        assertEquals(List.of("alipay", "mock"), chain);

        // 无商户配置命中（其他商户）→ 全局
        List<String> globalChain = service.resolveFallbackChain(99L, "chain",
                BigDecimal.valueOf(100), "NEX");
        assertEquals(List.of("mock", "wechat"), globalChain);
    }

    @Test
    @DisplayName("resolveFallbackChain: 条件不命中 → 空链；primary 为空 → 空链")
    void conditionsFilter() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(
                config(42L, "chain", "alipay", "{\"currency\":\"USD\"}", 1)));

        assertEquals(List.of(), service.resolveFallbackChain(42L, "chain",
                BigDecimal.valueOf(100), "NEX"));
        assertEquals(List.of(), service.resolveFallbackChain(42L, null,
                BigDecimal.valueOf(100), "USD"));
    }

    @Test
    @DisplayName("resolveFallbackChain: 金额条件命中")
    void amountConditions() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(
                config(42L, "chain", "alipay", "{\"amount_gte\":\"10000\"}", 1)));

        assertEquals(List.of(), service.resolveFallbackChain(42L, "chain",
                BigDecimal.valueOf(9999), "NEX"));
        assertEquals(List.of("alipay"), service.resolveFallbackChain(42L, "chain",
                BigDecimal.valueOf(10000), "NEX"));
    }

    @Test
    @DisplayName("create: 主渠道/备选 CSV 为空被拒绝")
    void createValidation() {
        FallbackRouteConfig noPrimary = config(42L, null, "alipay", null, 0);
        assertThrows(IllegalArgumentException.class, () -> service.create(noPrimary));

        FallbackRouteConfig noCsv = config(42L, "chain", " , ,", null, 0);
        assertThrows(IllegalArgumentException.class, () -> service.create(noCsv));

        FallbackRouteConfig badJson = config(42L, "chain", "alipay", "{invalid", 0);
        assertThrows(IllegalArgumentException.class, () -> service.create(badJson));
    }
}

/**
 * {@link CompensationRoutingService} 独立测试类（同文件成对，遵循项目成对测试风格）。
 */
class CompensationRoutingServiceTest {

    private CompensationRoutingRecordRepository recordRepository;
    private FallbackRouteService fallbackRouteService;
    private CompensationRoutingService service;

    @BeforeEach
    void setUp() {
        recordRepository = mock(CompensationRoutingRecordRepository.class);
        fallbackRouteService = mock(FallbackRouteService.class);
        service = new CompensationRoutingService(recordRepository, fallbackRouteService);
    }

    @Test
    @DisplayName("route: 降级链首选择补偿渠道；attempt_no 递增")
    void routePicksFirstFallback() {
        when(fallbackRouteService.resolveFallbackChain(42L, "chain", BigDecimal.valueOf(100), "NEX"))
                .thenReturn(List.of("alipay", "mock"));
        when(recordRepository.findByPaymentIdOrderByAttemptNoAsc("pay-1")).thenReturn(List.of());
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompensationRoutingRecord record = service.route(42L, "pay-1", null,
                "chain", BigDecimal.valueOf(100), "NEX");
        assertEquals("alipay", record.getCompensationConnector());
        assertEquals("PENDING", record.getResult());
        assertEquals(1, record.getAttemptNo());
        assertTrue(record.getCompensationId().startsWith("comp_"));
    }

    @Test
    @DisplayName("route: 同 compensationId 幂等返回已有记录")
    void routeIdempotent() {
        CompensationRoutingRecord existing = new CompensationRoutingRecord();
        existing.setCompensationId("comp_x");
        existing.setResult("SUCCESS");
        when(recordRepository.findByCompensationId("comp_x")).thenReturn(Optional.of(existing));

        CompensationRoutingRecord result = service.route(42L, "pay-1", "comp_x",
                "chain", BigDecimal.valueOf(100), "NEX");
        assertEquals("SUCCESS", result.getResult());
        verify(recordRepository, never()).save(any());
    }

    @Test
    @DisplayName("route: 无降级链 → FAILED + 原因说明")
    void routeNoChainFails() {
        when(fallbackRouteService.resolveFallbackChain(any(), any(), any(), any())).thenReturn(List.of());
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompensationRoutingRecord record = service.route(42L, "pay-1", null,
                "chain", BigDecimal.valueOf(100), "NEX");
        assertEquals("FAILED", record.getResult());
        assertNull(record.getCompensationConnector());
        assertTrue(record.getErrorMessage().contains("no cross-channel fallback route"));
    }

    @Test
    @DisplayName("complete: PENDING → SUCCESS；重复回填被拒绝")
    void completeTransition() {
        CompensationRoutingRecord pending = new CompensationRoutingRecord();
        pending.setCompensationId("comp_x");
        pending.setResult("PENDING");
        when(recordRepository.findByCompensationId("comp_x")).thenReturn(Optional.of(pending));
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CompensationRoutingRecord done = service.complete("comp_x",
                CompensationRoutingRecord.RouteResult.SUCCESS, 120L, null);
        assertEquals("SUCCESS", done.getResult());
        assertEquals(120L, done.getLatencyMs());

        done.setResult("SUCCESS");
        assertThrows(IllegalArgumentException.class, () -> service.complete("comp_x",
                CompensationRoutingRecord.RouteResult.FAILED, null, null));
    }
}
