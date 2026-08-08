package org.nexus.bridge.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.BridgeService;
import org.nexus.bridge.BridgeStatus;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DailyLimitAuditService} 单元测试：覆盖日限额快照、历史查询。
 */
@ExtendWith(MockitoExtension.class)
class DailyLimitAuditServiceTest {

    @Mock
    private BridgeService bridgeService;

    @Mock
    private BridgeStatus bridgeStatus;

    private DailyLimitAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new DailyLimitAuditService(bridgeService);
    }

    @Test
    @DisplayName("dailyReset: 应记录昨日使用量快照")
    void dailyReset_recordsSnapshot() {
        when(bridgeService.getStatus()).thenReturn(bridgeStatus);
        when(bridgeStatus.getDailyUsed()).thenReturn(50_000_000_000L);
        when(bridgeStatus.getDailyRemaining()).thenReturn(50_000_000_000L);
        when(bridgeStatus.getDailyLimit()).thenReturn(100_000_000_000L);

        auditService.dailyReset();

        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        Long used = auditService.getYesterdayUsed();
        assertNotNull(used);
        assertEquals(50_000_000_000L, used);
    }

    @Test
    @DisplayName("getYesterdayUsed: 未执行 dailyReset 时返回 null")
    void getYesterdayUsed_noSnapshotReturnsNull() {
        assertNull(auditService.getYesterdayUsed());
    }

    @Test
    @DisplayName("getUsageForDate: 指定日期查询")
    void getUsageForDate_specificDate() {
        when(bridgeService.getStatus()).thenReturn(bridgeStatus);
        when(bridgeStatus.getDailyUsed()).thenReturn(30_000_000_000L);
        when(bridgeStatus.getDailyRemaining()).thenReturn(70_000_000_000L);
        when(bridgeStatus.getDailyLimit()).thenReturn(100_000_000_000L);

        auditService.dailyReset();

        String yesterday = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1).toString();
        Long used = auditService.getUsageForDate(yesterday);
        assertNotNull(used);
        assertEquals(30_000_000_000L, used);
    }

    @Test
    @DisplayName("getUsageForDate: 未知日期返回 null")
    void getUsageForDate_unknownDateReturnsNull() {
        assertNull(auditService.getUsageForDate("2020-01-01"));
    }

    @Test
    @DisplayName("getFullHistory: 返回完整历史快照")
    void getFullHistory_returnsAllSnapshots() {
        when(bridgeService.getStatus()).thenReturn(bridgeStatus);
        when(bridgeStatus.getDailyUsed()).thenReturn(10_000_000_000L);
        when(bridgeStatus.getDailyRemaining()).thenReturn(90_000_000_000L);
        when(bridgeStatus.getDailyLimit()).thenReturn(100_000_000_000L);

        auditService.dailyReset();

        Map<String, Long> history = auditService.getFullHistory();
        assertFalse(history.isEmpty());
    }

    @Test
    @DisplayName("getFullHistory: 无快照时返回空映射")
    void getFullHistory_emptyWhenNoSnapshots() {
        Map<String, Long> history = auditService.getFullHistory();
        assertTrue(history.isEmpty());
    }
}