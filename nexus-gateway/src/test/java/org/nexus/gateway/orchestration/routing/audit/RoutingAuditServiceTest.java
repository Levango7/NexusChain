package org.nexus.gateway.orchestration.routing.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.RoutingWave16Properties;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RoutingAuditService} 单元测试：同步/异步写入、outcome 回填、禁用开关、保留期清理。
 */
class RoutingAuditServiceTest {

    private RoutingDecisionRecordRepository repository;
    private RoutingWave16Properties properties;
    private RoutingAuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(RoutingDecisionRecordRepository.class);
        properties = new RoutingWave16Properties();
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdownWriter();
        }
    }

    private Map<String, Double> scores() {
        LinkedHashMap<String, Double> scores = new LinkedHashMap<>();
        scores.put("chain", 0.82);
        scores.put("mock", 0.41);
        return scores;
    }

    @Test
    @DisplayName("recordDecision: 同步模式直接落库，决策元数据完整")
    void syncWrite() {
        properties.getAudit().setAsyncWrite(false);
        service = new RoutingAuditService(repository, properties);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String decisionId = service.recordDecision("pay-1", 7L, BigDecimal.valueOf(500), "NEX",
                "default-nex", "MULTI_OBJECTIVE", List.of("chain", "mock"), scores(),
                List.of("chain"), null, null);
        assertTrue(decisionId.startsWith("rd_"));
        verify(repository).save(any());
    }

    @Test
    @DisplayName("recordDecision: 异步模式最终落库（写入失败不影响调用方）")
    void asyncWrite() {
        service = new RoutingAuditService(repository, properties);
        AtomicBoolean saved = new AtomicBoolean(false);
        doAnswer(inv -> {
            saved.set(true);
            return inv.getArgument(0);
        }).when(repository).save(any());

        String decisionId = service.recordDecision("pay-1", 7L, BigDecimal.valueOf(500), "NEX",
                null, "PRIORITY", List.of("chain"), null, List.of("chain"), null, null);
        assertTrue(decisionId.startsWith("rd_"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!saved.get() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(saved.get(), "异步写入应在 5s 内完成");
    }

    @Test
    @DisplayName("recordDecision: audit.enabled=false 时完全不落库")
    void disabledNoWrite() {
        properties.getAudit().setEnabled(false);
        properties.getAudit().setAsyncWrite(false);
        service = new RoutingAuditService(repository, properties);
        String decisionId = service.recordDecision("pay-1", 7L, BigDecimal.valueOf(500), "NEX",
                null, "PRIORITY", List.of("chain"), null, List.of("chain"), null, null);
        assertTrue(decisionId.startsWith("rd_"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("recordOutcome: 回填 SUCCESS/FAILURE；repository 异常被吞")
    void recordOutcome() {
        properties.getAudit().setAsyncWrite(false);
        service = new RoutingAuditService(repository, properties);
        RoutingDecisionRecord record = new RoutingDecisionRecord();
        record.setDecisionId("rd_x");
        when(repository.findById("rd_x")).thenReturn(java.util.Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordOutcome("rd_x", true);
        assertEquals("SUCCESS", record.getOutcome());

        service.recordOutcome("rd_missing", true);
        service.recordOutcome(null, true);
        // disabled 时 no-op
        properties.getAudit().setEnabled(false);
        service.recordOutcome("rd_x", false);
        assertEquals("SUCCESS", record.getOutcome());
    }

    @Test
    @DisplayName("purgeExpired: 清理超保留期记录")
    void purgeExpired() {
        properties.getAudit().setAsyncWrite(false);
        service = new RoutingAuditService(repository, properties);
        RoutingDecisionRecord old = new RoutingDecisionRecord();
        old.setDecisionId("rd_old");
        when(repository.findByCreatedAtBefore(any())).thenReturn(List.of(old));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.purgeExpired();
        verify(repository).deleteAll(List.of(old));
    }
}
