package org.nexus.gateway.security.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ReplayProtectionConfigService 单元测试（Wave 12 REPLAY 模块）。
 *
 * <p>覆盖：getConfig 无配置时返回默认值；getConfig 有配置时返回持久化值；
 * updateConfig 参数范围校验（replayWindowMs / nonceMinLengthBytes / idempotencyTtlHours）；
 * updateConfig 正常保存流程。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReplayProtectionConfigServiceTest {

    @Mock
    private ReplayProtectionConfigRepository repository;

    @InjectMocks
    private ReplayProtectionConfigService service;

    // ===== getConfig =====

    @Test
    @DisplayName("getConfig: 无配置记录时返回默认值")
    void getConfig_noRecord_returnsDefaults() {
        when(repository.findByTenantId("tenant-001")).thenReturn(Optional.empty());

        ReplayProtectionConfig config = service.getConfig("tenant-001");

        assertEquals("tenant-001", config.getTenantId());
        assertEquals(180000L, config.getReplayWindowMs(), "默认 replayWindowMs 应为 180000");
        assertEquals(16, config.getNonceMinLengthBytes(), "默认 nonceMinLengthBytes 应为 16");
        assertEquals(24, config.getIdempotencyTtlHours(), "默认 idempotencyTtlHours 应为 24");
        verify(repository).findByTenantId("tenant-001");
    }

    @Test
    @DisplayName("getConfig: 有配置记录时返回持久化值")
    void getConfig_hasRecord_returnsPersistedValues() {
        ReplayProtectionConfig persisted = new ReplayProtectionConfig();
        persisted.setTenantId("tenant-002");
        persisted.setReplayWindowMs(120000L);
        persisted.setNonceMinLengthBytes(32);
        persisted.setIdempotencyTtlHours(48);
        when(repository.findByTenantId("tenant-002")).thenReturn(Optional.of(persisted));

        ReplayProtectionConfig config = service.getConfig("tenant-002");

        assertEquals("tenant-002", config.getTenantId());
        assertEquals(120000L, config.getReplayWindowMs());
        assertEquals(32, config.getNonceMinLengthBytes());
        assertEquals(48, config.getIdempotencyTtlHours());
        verify(repository).findByTenantId("tenant-002");
    }

    // ===== updateConfig: replayWindowMs 校验 =====

    @Test
    @DisplayName("updateConfig: replayWindowMs=null 抛出 IllegalArgumentException")
    void updateConfig_nullReplayWindowMs_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", null, 16, 24));
        assertTrue(ex.getMessage().contains("replayWindowMs"));
    }

    @Test
    @DisplayName("updateConfig: replayWindowMs < 60000 抛出 IllegalArgumentException")
    void updateConfig_replayWindowMsTooSmall_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 59999L, 16, 24));
        assertTrue(ex.getMessage().contains("replayWindowMs"));
    }

    @Test
    @DisplayName("updateConfig: replayWindowMs > 300000 抛出 IllegalArgumentException")
    void updateConfig_replayWindowMsTooLarge_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 300001L, 16, 24));
        assertTrue(ex.getMessage().contains("replayWindowMs"));
    }

    @Test
    @DisplayName("updateConfig: replayWindowMs=60000 通过（边界值）")
    void updateConfig_replayWindowMsMinBoundary_passes() {
        when(repository.findByTenantId("tenant-001")).thenReturn(Optional.empty());
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplayProtectionConfig result = service.updateConfig("tenant-001", 60000L, 16, 24);
        assertEquals(60000L, result.getReplayWindowMs());
    }

    @Test
    @DisplayName("updateConfig: replayWindowMs=300000 通过（边界值）")
    void updateConfig_replayWindowMsMaxBoundary_passes() {
        when(repository.findByTenantId("tenant-001")).thenReturn(Optional.empty());
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplayProtectionConfig result = service.updateConfig("tenant-001", 300000L, 16, 24);
        assertEquals(300000L, result.getReplayWindowMs());
    }

    // ===== updateConfig: nonceMinLengthBytes 校验 =====

    @Test
    @DisplayName("updateConfig: nonceMinLengthBytes=null 抛出 IllegalArgumentException")
    void updateConfig_nullNonceMinLengthBytes_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 180000L, null, 24));
        assertTrue(ex.getMessage().contains("nonceMinLengthBytes"));
    }

    @Test
    @DisplayName("updateConfig: nonceMinLengthBytes < 16 抛出 IllegalArgumentException")
    void updateConfig_nonceMinLengthBytesTooSmall_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 180000L, 15, 24));
        assertTrue(ex.getMessage().contains("nonceMinLengthBytes"));
    }

    @Test
    @DisplayName("updateConfig: nonceMinLengthBytes=16 通过（边界值）")
    void updateConfig_nonceMinLengthBytesMinBoundary_passes() {
        when(repository.findByTenantId("tenant-001")).thenReturn(Optional.empty());
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplayProtectionConfig result = service.updateConfig("tenant-001", 180000L, 16, 24);
        assertEquals(16, result.getNonceMinLengthBytes());
    }

    // ===== updateConfig: idempotencyTtlHours 校验 =====

    @Test
    @DisplayName("updateConfig: idempotencyTtlHours=null 抛出 IllegalArgumentException")
    void updateConfig_nullIdempotencyTtlHours_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 180000L, 16, null));
        assertTrue(ex.getMessage().contains("idempotencyTtlHours"));
    }

    @Test
    @DisplayName("updateConfig: idempotencyTtlHours < 1 抛出 IllegalArgumentException")
    void updateConfig_idempotencyTtlHoursTooSmall_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 180000L, 16, 0));
        assertTrue(ex.getMessage().contains("idempotencyTtlHours"));
    }

    @Test
    @DisplayName("updateConfig: idempotencyTtlHours > 168 抛出 IllegalArgumentException")
    void updateConfig_idempotencyTtlHoursTooLarge_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("tenant-001", 180000L, 16, 169));
        assertTrue(ex.getMessage().contains("idempotencyTtlHours"));
    }

    @Test
    @DisplayName("updateConfig: idempotencyTtlHours=1 通过（边界值）")
    void updateConfig_idempotencyTtlHoursMinBoundary_passes() {
        when(repository.findByTenantId("tenant-001")).thenReturn(Optional.empty());
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplayProtectionConfig result = service.updateConfig("tenant-001", 180000L, 16, 1);
        assertEquals(1, result.getIdempotencyTtlHours());
    }

    @Test
    @DisplayName("updateConfig: idempotencyTtlHours=168 通过（边界值）")
    void updateConfig_idempotencyTtlHoursMaxBoundary_passes() {
        when(repository.findByTenantId("tenant-001")).thenReturn(Optional.empty());
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplayProtectionConfig result = service.updateConfig("tenant-001", 180000L, 16, 168);
        assertEquals(168, result.getIdempotencyTtlHours());
    }

    // ===== updateConfig: 正常流程 =====

    @Test
    @DisplayName("updateConfig: 新租户创建配置并保存")
    void updateConfig_newTenant_createsAndSaves() {
        when(repository.findByTenantId("tenant-new")).thenReturn(Optional.empty());
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> {
            ReplayProtectionConfig saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        ReplayProtectionConfig result = service.updateConfig("tenant-new", 120000L, 32, 48);

        assertEquals("tenant-new", result.getTenantId());
        assertEquals(120000L, result.getReplayWindowMs());
        assertEquals(32, result.getNonceMinLengthBytes());
        assertEquals(48, result.getIdempotencyTtlHours());
        verify(repository).findByTenantId("tenant-new");
        verify(repository).save(any(ReplayProtectionConfig.class));
    }

    @Test
    @DisplayName("updateConfig: 已有租户更新配置并保存")
    void updateConfig_existingTenant_updatesAndSaves() {
        ReplayProtectionConfig existing = new ReplayProtectionConfig();
        existing.setId(5L);
        existing.setTenantId("tenant-005");
        existing.setReplayWindowMs(180000L);
        existing.setNonceMinLengthBytes(16);
        existing.setIdempotencyTtlHours(24);

        when(repository.findByTenantId("tenant-005")).thenReturn(Optional.of(existing));
        when(repository.save(any(ReplayProtectionConfig.class))).thenAnswer(inv -> inv.getArgument(0));

        ReplayProtectionConfig result = service.updateConfig("tenant-005", 90000L, 16, 12);

        assertEquals(5L, result.getId(), "应保留原有 ID");
        assertEquals("tenant-005", result.getTenantId());
        assertEquals(90000L, result.getReplayWindowMs());
        assertEquals(16, result.getNonceMinLengthBytes());
        assertEquals(12, result.getIdempotencyTtlHours());
        verify(repository).findByTenantId("tenant-005");
        verify(repository).save(existing);
    }
}