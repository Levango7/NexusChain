package org.nexus.gateway.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultTenantService} 单元测试（P4-T6 多租户改造）。
 *
 * <p>覆盖租户 CRUD、状态流转（暂停/终止/恢复）和 API Key 验证。</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantServiceTest {

    @Mock
    private TenantRepository tenantRepository;

    private DefaultTenantService tenantService;

    @BeforeEach
    void setUp() {
        tenantService = new DefaultTenantService(tenantRepository);
    }

    private Tenant sampleTenant(String tenantId, String apiKey, TenantStatus status) {
        Tenant t = new Tenant();
        t.setTenantId(tenantId);
        t.setName("Test Tenant");
        t.setApiKey(apiKey);
        t.setApiSecret("secret-hash");
        t.setStatus(status);
        t.setConfig(new TenantConfig());
        return t;
    }

    @Test
    @DisplayName("createTenant: 应生成 tenantId/apiKey/apiSecret 并持久化")
    void createTenantGeneratesIds() {
        Tenant input = new Tenant();
        input.setName("Acme Corp");
        when(tenantRepository.existsByTenantId(anyString())).thenReturn(false);
        when(tenantRepository.existsByApiKey(anyString())).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant created = tenantService.createTenant(input);

        assertNotNull(created.getTenantId());
        assertNotNull(created.getApiKey());
        assertNotNull(created.getApiSecret());
        assertEquals(TenantStatus.ACTIVE, created.getStatus());
        assertNotNull(created.getConfig());
        verify(tenantRepository).save(any(Tenant.class));
    }

    @Test
    @DisplayName("createTenant: tenantId 重复时抛异常")
    void createTenantDuplicateTenantId() {
        Tenant input = sampleTenant("dup-tenant-id", "key-1", TenantStatus.ACTIVE);
        when(tenantRepository.existsByTenantId("dup-tenant-id")).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tenantService.createTenant(input));
        assertTrue(ex.getMessage().contains("Tenant ID already exists"));
    }

    @Test
    @DisplayName("createTenant: apiKey 重复时抛异常")
    void createTenantDuplicateApiKey() {
        Tenant input = sampleTenant("new-tenant", "dup-key", TenantStatus.ACTIVE);
        when(tenantRepository.existsByTenantId("new-tenant")).thenReturn(false);
        when(tenantRepository.existsByApiKey("dup-key")).thenReturn(true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tenantService.createTenant(input));
        assertTrue(ex.getMessage().contains("API Key already exists"));
    }

    @Test
    @DisplayName("getTenant: 应委托 repository 查询")
    void getTenantDelegatesToRepository() {
        Tenant t = sampleTenant("t-1", "k-1", TenantStatus.ACTIVE);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(t));

        Optional<Tenant> result = tenantService.getTenant("t-1");

        assertTrue(result.isPresent());
        assertEquals("t-1", result.get().getTenantId());
    }

    @Test
    @DisplayName("updateTenant: 应更新 name 和 config")
    void updateTenantUpdatesFields() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.ACTIVE);
        existing.setId(1L);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant update = new Tenant();
        update.setName("Updated Name");
        TenantConfig newConfig = new TenantConfig();
        newConfig.setFeeRateBps(200);
        update.setConfig(newConfig);

        Tenant result = tenantService.updateTenant("t-1", update);

        assertEquals("Updated Name", result.getName());
        assertEquals(200, result.getConfig().getFeeRateBps());
    }

    @Test
    @DisplayName("updateTenant: 租户不存在时抛异常")
    void updateTenantNotFound() {
        when(tenantRepository.findByTenantId("ghost")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> tenantService.updateTenant("ghost", new Tenant()));
    }

    @Test
    @DisplayName("suspendTenant: ACTIVE → SUSPENDED")
    void suspendTenantTransitionsToSuspended() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.ACTIVE);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.suspendTenant("t-1");

        assertEquals(TenantStatus.SUSPENDED, result.getStatus());
    }

    @Test
    @DisplayName("suspendTenant: 非 ACTIVE 状态时抛异常")
    void suspendTenantIllegalState() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.TERMINATED);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> tenantService.suspendTenant("t-1"));
    }

    @Test
    @DisplayName("terminateTenant: 任意非 TERMINATED 状态 → TERMINATED")
    void terminateTenantTransitionsToTerminated() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.SUSPENDED);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.terminateTenant("t-1");

        assertEquals(TenantStatus.TERMINATED, result.getStatus());
    }

    @Test
    @DisplayName("terminateTenant: 已 TERMINATED 时抛异常")
    void terminateTenantAlreadyTerminated() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.TERMINATED);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> tenantService.terminateTenant("t-1"));
    }

    @Test
    @DisplayName("activateTenant: SUSPENDED → ACTIVE")
    void activateTenantTransitionsToActive() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.SUSPENDED);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = tenantService.activateTenant("t-1");

        assertEquals(TenantStatus.ACTIVE, result.getStatus());
    }

    @Test
    @DisplayName("activateTenant: 非 SUSPENDED 状态时抛异常")
    void activateTenantIllegalState() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.TERMINATED);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class, () -> tenantService.activateTenant("t-1"));
    }

    @Test
    @DisplayName("validateApiKey: ACTIVE 租户验证通过")
    void validateApiKeyActiveTenant() {
        Tenant t = sampleTenant("t-1", "valid-key", TenantStatus.ACTIVE);
        when(tenantRepository.findByApiKey("valid-key")).thenReturn(Optional.of(t));

        Optional<Tenant> result = tenantService.validateApiKey("valid-key");

        assertTrue(result.isPresent());
        assertEquals("t-1", result.get().getTenantId());
    }

    @Test
    @DisplayName("validateApiKey: SUSPENDED 租户验证拒绝")
    void validateApiKeySuspendedTenantRejected() {
        Tenant t = sampleTenant("t-1", "suspended-key", TenantStatus.SUSPENDED);
        when(tenantRepository.findByApiKey("suspended-key")).thenReturn(Optional.of(t));

        Optional<Tenant> result = tenantService.validateApiKey("suspended-key");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("validateApiKey: TERMINATED 租户验证拒绝")
    void validateApiKeyTerminatedTenantRejected() {
        Tenant t = sampleTenant("t-1", "terminated-key", TenantStatus.TERMINATED);
        when(tenantRepository.findByApiKey("terminated-key")).thenReturn(Optional.of(t));

        Optional<Tenant> result = tenantService.validateApiKey("terminated-key");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("validateApiKey: 不存在的 key 返回 empty")
    void validateApiKeyNotFound() {
        when(tenantRepository.findByApiKey("ghost-key")).thenReturn(Optional.empty());

        Optional<Tenant> result = tenantService.validateApiKey("ghost-key");

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("validateApiKey: null/空 key 返回 empty")
    void validateApiKeyNullEmpty() {
        assertTrue(tenantService.validateApiKey(null).isEmpty());
        assertTrue(tenantService.validateApiKey("").isEmpty());
    }

    @Test
    @DisplayName("updateTenantConfig: 应更新配置")
    void updateTenantConfigUpdatesConfig() {
        Tenant existing = sampleTenant("t-1", "k-1", TenantStatus.ACTIVE);
        when(tenantRepository.findByTenantId("t-1")).thenReturn(Optional.of(existing));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        TenantConfig newConfig = new TenantConfig();
        newConfig.setRateLimitPerSecond(500);
        newConfig.setFeeRateBps(50);

        Tenant result = tenantService.updateTenantConfig("t-1", newConfig);

        assertEquals(500, result.getConfig().getRateLimitPerSecond());
        assertEquals(50, result.getConfig().getFeeRateBps());
    }
}