package org.nexus.bridge.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.bridge.BridgeService;
import org.nexus.bridge.BridgeStatus;
import org.nexus.bridge.repository.BridgeDailyLimitSnapshotRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 日限额审计快照落库测试（PLAN-038）。
 *
 * <p>锁定：快照写入 {@code bridge_daily_limit_snapshot} 表；重启语义（新实例
 * 同仓库可读历史）；同日重复触发为幂等覆盖（多副本午夜重复触发无副作用）。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DailyLimitAuditServicePersistenceTest {

    @Autowired
    private BridgeDailyLimitSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private String yesterday() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
    }

    private DailyLimitAuditService newService(long dailyUsed) {
        BridgeService bridgeService = Mockito.mock(BridgeService.class);
        BridgeStatus status = new BridgeStatus();
        status.setDailyUsed(dailyUsed);
        status.setDailyLimit(100L);
        when(bridgeService.getStatus()).thenReturn(status);

        DailyLimitAuditService service = new DailyLimitAuditService(bridgeService);
        ReflectionTestUtils.setField(service, "snapshotRepository", repository);
        return service;
    }

    @Test
    void dailyResetPersistsSnapshotAndSurvivesRestart() {
        newService(42L).dailyReset();

        assertEquals(42L, repository.findById(yesterday()).orElseThrow().getUsed());

        // 重启语义：新服务实例（同仓库）仍能读到历史
        DailyLimitAuditService restarted = newService(0L);
        assertEquals(42L, restarted.getUsageForDate(yesterday()));
        assertEquals(42L, restarted.getYesterdayUsed());
        assertTrue(restarted.getFullHistory().containsKey(yesterday()));
    }

    @Test
    void sameDaySnapshotIsIdempotentOverwrite() {
        newService(10L).dailyReset();
        newService(90L).dailyReset();

        assertEquals(90L, repository.findById(yesterday()).orElseThrow().getUsed(),
                "同日重复触发应为幂等覆盖（取最新快照）");
        assertEquals(1L, repository.count(), "同日只应有一行");
    }
}
