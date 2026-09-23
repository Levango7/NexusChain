package org.nexus.gateway.apiversion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ApiVersionGovernanceController 单元测试。
 *
 * <p>测试版本治理 API 端点：策略列表、策略详情、策略更新、迁移指南。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApiVersionGovernanceControllerTest {

    @Mock
    private ApiVersionDeprecationService deprecationService;

    private ApiVersionGovernanceController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiVersionGovernanceController(deprecationService);
    }

    // === GET /policies 测试 ===

    @Test
    @DisplayName("GET /policies — 列出所有版本策略")
    void testListPolicies() {
        ApiVersionPolicy p1 = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        ApiVersionPolicy p2 = createPolicy("v2", ApiVersionPolicy.VersionStatus.ACTIVE);
        when(deprecationService.listAllPolicies()).thenReturn(List.of(p1, p2));

        ResponseEntity<List<ApiVersionPolicy>> result = controller.listPolicies();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(2, result.getBody().size());
    }

    @Test
    @DisplayName("GET /policies — 无策略时返回空列表")
    void testListPoliciesEmpty() {
        when(deprecationService.listAllPolicies()).thenReturn(List.of());

        ResponseEntity<List<ApiVersionPolicy>> result = controller.listPolicies();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertTrue(result.getBody().isEmpty());
    }

    // === GET /policies/{version} 测试 ===

    @Test
    @DisplayName("GET /policies/{version} — 版本存在时返回策略详情")
    void testGetPolicyFound() {
        ApiVersionPolicy policy = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(deprecationService.getPolicy("v1")).thenReturn(Optional.of(policy));

        ResponseEntity<ApiVersionPolicy> result = controller.getPolicy("v1");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("v1", result.getBody().getVersion());
    }

    @Test
    @DisplayName("GET /policies/{version} — 版本不存在时返回 404")
    void testGetPolicyNotFound() {
        when(deprecationService.getPolicy("v3")).thenReturn(Optional.empty());

        ResponseEntity<ApiVersionPolicy> result = controller.getPolicy("v3");

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    // === PUT /policies/{version} 测试 ===

    @Test
    @DisplayName("PUT /policies/{version} — 成功更新策略")
    void testUpdatePolicySuccess() {
        ApiVersionPolicy updated = createPolicy("v1", ApiVersionPolicy.VersionStatus.SUNSET);
        updated.setSunsetAt(LocalDate.of(2027, 3, 1));
        when(deprecationService.updatePolicy(eq("v1"), any(), any(), any(), any()))
                .thenReturn(Optional.of(updated));

        Map<String, Object> body = Map.of(
                "status", "SUNSET",
                "sunsetAt", "2027-03-01",
                "successorVersion", "v2",
                "migrationGuide", "Updated guide");

        ResponseEntity<ApiVersionPolicy> result = controller.updatePolicy("v1", body);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals(ApiVersionPolicy.VersionStatus.SUNSET, result.getBody().getStatus());
    }

    @Test
    @DisplayName("PUT /policies/{version} — 版本不存在时返回 404")
    void testUpdatePolicyNotFound() {
        when(deprecationService.updatePolicy(eq("v3"), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        Map<String, Object> body = Map.of("status", "DEPRECATED");

        ResponseEntity<ApiVersionPolicy> result = controller.updatePolicy("v3", body);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    @Test
    @DisplayName("PUT /policies/{version} — 无效 status 返回 400")
    void testUpdatePolicyInvalidStatus() {
        Map<String, Object> body = Map.of("status", "INVALID_STATUS");

        ResponseEntity<ApiVersionPolicy> result = controller.updatePolicy("v1", body);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    @DisplayName("PUT /policies/{version} — 无效 sunsetAt 返回 400")
    void testUpdatePolicyInvalidSunsetDate() {
        Map<String, Object> body = Map.of(
                "status", "DEPRECATED",
                "sunsetAt", "not-a-date");

        ResponseEntity<ApiVersionPolicy> result = controller.updatePolicy("v1", body);

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    @DisplayName("PUT /policies/{version} — 状态变为 DEPRECATED 时发布事件")
    void testUpdatePolicyPublishesEventOnDeprecated() {
        ApiVersionPolicy updated = createPolicy("v1", ApiVersionPolicy.VersionStatus.DEPRECATED);
        when(deprecationService.updatePolicy(eq("v1"), any(), any(), any(), any()))
                .thenReturn(Optional.of(updated));

        Map<String, Object> body = Map.of("status", "DEPRECATED");

        controller.updatePolicy("v1", body);

        verify(deprecationService).notifyDeprecation("v1");
    }

    @Test
    @DisplayName("PUT /policies/{version} — 状态变为 ACTIVE 时不发布事件")
    void testUpdatePolicyNoEventOnActive() {
        ApiVersionPolicy updated = createPolicy("v1", ApiVersionPolicy.VersionStatus.ACTIVE);
        when(deprecationService.updatePolicy(eq("v1"), any(), any(), any(), any()))
                .thenReturn(Optional.of(updated));

        Map<String, Object> body = Map.of("status", "ACTIVE");

        controller.updatePolicy("v1", body);

        verify(deprecationService, never()).notifyDeprecation(any());
    }

    // === GET /migration-guide/{from}/{to} 测试 ===

    @Test
    @DisplayName("GET /migration-guide/{from}/{to} — 返回迁移指南")
    void testGetMigrationGuideFound() {
        when(deprecationService.getMigrationGuide("v1", "v2"))
                .thenReturn(Optional.of("Migrate from v1 to v2: ..."));

        ResponseEntity<Map<String, String>> result = controller.getMigrationGuide("v1", "v2");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertNotNull(result.getBody());
        assertEquals("v1", result.getBody().get("from"));
        assertEquals("v2", result.getBody().get("to"));
        assertEquals("Migrate from v1 to v2: ...", result.getBody().get("migrationGuide"));
    }

    @Test
    @DisplayName("GET /migration-guide/{from}/{to} — 不存在时返回 404")
    void testGetMigrationGuideNotFound() {
        when(deprecationService.getMigrationGuide("v3", "v4"))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> result = controller.getMigrationGuide("v3", "v4");

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
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