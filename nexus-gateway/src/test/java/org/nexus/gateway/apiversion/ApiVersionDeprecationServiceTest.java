package org.nexus.gateway.apiversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ApiVersionDeprecationService 单元测试。
 *
 * <p>测试版本状态检查、废弃头生成、策略更新与事件发布等核心逻辑。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApiVersionDeprecationServiceTest {

    @Mock
    private ApiVersionPolicyRepository policyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ApiVersionDeprecationService service;

    @BeforeEach
    void setUp() {
        service = new ApiVersionDeprecationService(policyRepository, eventPublisher);
    }

    // === getPolicy 测试 ===

    @Test
    @DisplayName("getPolicy: 版本存在时返回策略")
    void testGetPolicyFound() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        Optional<ApiVersionPolicy> result = service.getPolicy("v1");

        assertTrue(result.isPresent());
        assertEquals("v1", result.get().getVersion());
    }

    @Test
    @DisplayName("getPolicy: 版本不存在时返回空")
    void testGetPolicyNotFound() {
        when(policyRepository.findByVersion("v3")).thenReturn(Optional.empty());

        Optional<ApiVersionPolicy> result = service.getPolicy("v3");

        assertTrue(result.isEmpty());
    }

    // === checkVersionStatus 测试 ===

    @Test
    @DisplayName("checkVersionStatus: 策略不存在时默认 ACTIVE")
    void testCheckVersionStatusNoPolicy() {
        when(policyRepository.findByVersion("v3")).thenReturn(Optional.empty());

        assertEquals(ApiVersionPolicy.VersionStatus.ACTIVE, service.checkVersionStatus("v3"));
    }

    @Test
    @DisplayName("checkVersionStatus: ACTIVE 版本返回 ACTIVE")
    void testCheckVersionStatusActive() {
        ApiVersionPolicy policy = createPolicy("v2", ApiVersionPolicy.VersionStatus.ACTIVE);
        when(policyRepository.findByVersion("v2")).thenReturn(Optional.of(policy));

        assertEquals(ApiVersionPolicy.VersionStatus.ACTIVE, service.checkVersionStatus("v2"));
    }

    @Test
    @DisplayName("checkVersionStatus: DEPRECATED 版本返回 DEPRECATED")
    void testCheckVersionStatusDeprecated() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSunsetAt(LocalDate.now().plusMonths(6));
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        assertEquals(ApiVersionPolicy.VersionStatus.DEPRECATED, service.checkVersionStatus("v1"));
    }

    @Test
    @DisplayName("checkVersionStatus: DEPRECATED 且 Sunset 日期已过 → 自动视为 SUNSET")
    void testCheckVersionStatusDeprecatedPastSunset() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSunsetAt(LocalDate.now().minusDays(1));
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        assertEquals(ApiVersionPolicy.VersionStatus.SUNSET, service.checkVersionStatus("v1"));
    }

    @Test
    @DisplayName("checkVersionStatus: SUNSET 版本返回 SUNSET")
    void testCheckVersionStatusSunset() {
        ApiVersionPolicy policy = createPolicy("v0", ApiVersionPolicy.VersionStatus.SUNSET);
        when(policyRepository.findByVersion("v0")).thenReturn(Optional.of(policy));

        assertEquals(ApiVersionPolicy.VersionStatus.SUNSET, service.checkVersionStatus("v0"));
    }

    @Test
    @DisplayName("checkVersionStatus: RETIRED 版本返回 RETIRED")
    void testCheckVersionStatusRetired() {
        ApiVersionPolicy policy = createPolicy("v0", ApiVersionPolicy.VersionStatus.RETIRED);
        when(policyRepository.findByVersion("v0")).thenReturn(Optional.of(policy));

        assertEquals(ApiVersionPolicy.VersionStatus.RETIRED, service.checkVersionStatus("v0"));
    }

    // === getDeprecationHeaders 测试 ===

    @Test
    @DisplayName("getDeprecationHeaders: DEPRECATED 版本生成完整头")
    void testGetDeprecationHeadersDeprecated() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSunsetAt(LocalDate.of(2027, 2, 9));
        policy.setSuccessorVersion("v2");
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        Map<String, String> headers = service.getDeprecationHeaders("v1");

        assertEquals("true", headers.get("Deprecation"));
        assertEquals("2027-02-09", headers.get("Sunset"));
        assertEquals("</api/v2/orders>; rel=\"successor-version\"", headers.get("Link"));
    }

    @Test
    @DisplayName("getDeprecationHeaders: ACTIVE 版本返回空 Map")
    void testGetDeprecationHeadersActive() {
        ApiVersionPolicy policy = createPolicy("v2", ApiVersionPolicy.VersionStatus.ACTIVE);
        when(policyRepository.findByVersion("v2")).thenReturn(Optional.of(policy));

        Map<String, String> headers = service.getDeprecationHeaders("v2");

        assertTrue(headers.isEmpty());
    }

    @Test
    @DisplayName("getDeprecationHeaders: 策略不存在时返回空 Map")
    void testGetDeprecationHeadersNoPolicy() {
        when(policyRepository.findByVersion("v3")).thenReturn(Optional.empty());

        Map<String, String> headers = service.getDeprecationHeaders("v3");

        assertTrue(headers.isEmpty());
    }

    @Test
    @DisplayName("getDeprecationHeaders: DEPRECATED 但无 Sunset 日期时不生成 Sunset 头")
    void testGetDeprecationHeadersNoSunsetDate() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSuccessorVersion("v2");
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        Map<String, String> headers = service.getDeprecationHeaders("v1");

        assertEquals("true", headers.get("Deprecation"));
        assertNull(headers.get("Sunset"));
        assertEquals("</api/v2/orders>; rel=\"successor-version\"", headers.get("Link"));
    }

    @Test
    @DisplayName("getDeprecationHeaders: DEPRECATED 但无后继版本时不生成 Link 头")
    void testGetDeprecationHeadersNoSuccessor() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSunsetAt(LocalDate.of(2027, 2, 9));
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        Map<String, String> headers = service.getDeprecationHeaders("v1");

        assertEquals("true", headers.get("Deprecation"));
        assertEquals("2027-02-09", headers.get("Sunset"));
        assertNull(headers.get("Link"));
    }

    // === listAllPolicies 测试 ===

    @Test
    @DisplayName("listAllPolicies: 返回所有策略列表")
    void testListAllPolicies() {
        ApiVersionPolicy p1 = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        ApiVersionPolicy p2 = createPolicy("v2", ApiVersionPolicy.VersionStatus.ACTIVE);
        when(policyRepository.findAll()).thenReturn(List.of(p1, p2));

        List<ApiVersionPolicy> policies = service.listAllPolicies();

        assertEquals(2, policies.size());
    }

    // === updatePolicy 测试 ===

    @Test
    @DisplayName("updatePolicy: 成功更新策略")
    void testUpdatePolicySuccess() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<ApiVersionPolicy> result = service.updatePolicy(
                "v1",
                ApiVersionPolicy.VersionStatus.SUNSET,
                LocalDate.of(2027, 3, 1),
                "v2",
                "Updated migration guide");

        assertTrue(result.isPresent());
        assertEquals(ApiVersionPolicy.VersionStatus.SUNSET, result.get().getStatus());
        assertEquals(LocalDate.of(2027, 3, 1), result.get().getSunsetAt());
        assertEquals("v2", result.get().getSuccessorVersion());
        assertEquals("Updated migration guide", result.get().getMigrationGuide());
    }

    @Test
    @DisplayName("updatePolicy: 版本不存在时返回空")
    void testUpdatePolicyNotFound() {
        when(policyRepository.findByVersion("v3")).thenReturn(Optional.empty());

        Optional<ApiVersionPolicy> result = service.updatePolicy(
                "v3", ApiVersionPolicy.VersionStatus.DEPRECATED, null, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("updatePolicy: null 字段不覆盖现有值")
    void testUpdatePolicyNullFieldsPreserved() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSunsetAt(LocalDate.of(2027, 2, 9));
        policy.setSuccessorVersion("v2");
        policy.setMigrationGuide("Original guide");
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 仅更新 status，其他字段传 null
        Optional<ApiVersionPolicy> result = service.updatePolicy(
                "v1", ApiVersionPolicy.VersionStatus.SUNSET, null, null, null);

        assertTrue(result.isPresent());
        assertEquals(ApiVersionPolicy.VersionStatus.SUNSET, result.get().getStatus());
        assertEquals(LocalDate.of(2027, 2, 9), result.get().getSunsetAt());
        assertEquals("v2", result.get().getSuccessorVersion());
        assertEquals("Original guide", result.get().getMigrationGuide());
    }

    // === notifyDeprecation 测试 ===

    @Test
    @DisplayName("notifyDeprecation: 发布 ApiVersionDeprecatedEvent")
    void testNotifyDeprecationPublishesEvent() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        service.notifyDeprecation("v1");

        ArgumentCaptor<ApiVersionDeprecatedEvent> captor =
                ArgumentCaptor.forClass(ApiVersionDeprecatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(policy, captor.getValue().getPolicy());
    }

    @Test
    @DisplayName("notifyDeprecation: 策略不存在时不发布事件")
    void testNotifyDeprecationNoPolicy() {
        when(policyRepository.findByVersion("v3")).thenReturn(Optional.empty());

        service.notifyDeprecation("v3");

        verify(eventPublisher, never()).publishEvent(any());
    }

    // === getMigrationGuide 测试 ===

    @Test
    @DisplayName("getMigrationGuide: 返回迁移指南文本")
    void testGetMigrationGuideFound() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        policy.setSuccessorVersion("v2");
        policy.setMigrationGuide("Migrate from v1 to v2");
        when(policyRepository.findByVersion("v1")).thenReturn(Optional.of(policy));

        Optional<String> guide = service.getMigrationGuide("v1", "v2");

        assertTrue(guide.isPresent());
        assertEquals("Migrate from v1 to v2", guide.get());
    }

    @Test
    @DisplayName("getMigrationGuide: 策略不存在时返回空")
    void testGetMigrationGuideNotFound() {
        when(policyRepository.findByVersion("v3")).thenReturn(Optional.empty());

        Optional<String> guide = service.getMigrationGuide("v3", "v4");

        assertTrue(guide.isEmpty());
    }

    // === 辅助方法 ===

    private ApiVersionPolicy createPolicy(String version, ApiVersionPolicy.VersionStatus status) {
        ApiVersionPolicy policy = new ApiVersionPolicy();
        policy.setId(1L);
        policy.setVersion(version);
        policy.setStatus(status);
        return policy;
    }
}