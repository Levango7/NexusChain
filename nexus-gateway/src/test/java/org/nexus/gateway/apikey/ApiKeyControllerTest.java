package org.nexus.gateway.apikey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ApiKeyController} 单元测试。
 *
 * <p>覆盖 CRUD API 端点：创建/列出/详情/轮换/撤销。
 * 使用 Mockito mock {@link ApiKeyService}，不依赖 Spring MVC 上下文。</p>
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyControllerTest {

    @Mock
    private ApiKeyService apiKeyService;

    private ApiKeyController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiKeyController(apiKeyService);
    }

    // === 创建 ===

    @Test
    @DisplayName("POST /api-keys：创建成功返回 201 + 明文密钥")
    void createApiKeyReturns201WithSecret() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey apiKey = createApiKey("ak_live_new001", "merchant-1", "PAYMENTS,REFUNDS");
        ApiKeyService.CreateApiKeyResult result =
                new ApiKeyService.CreateApiKeyResult(apiKey, "plain-secret-value");

        when(apiKeyService.createApiKey(eq("merchant-1"), eq("PAYMENTS,REFUNDS"),
                eq("测试Key"), any())).thenReturn(result);

        ApiKeyController.CreateApiKeyRequest body = new ApiKeyController.CreateApiKeyRequest();
        body.setScopes("PAYMENTS,REFUNDS");
        body.setDescription("测试Key");
        body.setExpireDays(30);

        ResponseEntity<Map<String, Object>> response =
                controller.createApiKey(request, body);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ak_live_new001", response.getBody().get("keyId"));
        assertEquals("plain-secret-value", response.getBody().get("keySecret"));
        assertEquals("merchant-1", response.getBody().get("merchantId"));
    }

    @Test
    @DisplayName("POST /api-keys：无租户上下文抛异常")
    void createApiKeyThrowsWithoutTenantContext() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // 不设置 nexus.tenantId

        ApiKeyController.CreateApiKeyRequest body = new ApiKeyController.CreateApiKeyRequest();
        body.setScopes("PAYMENTS");

        assertThrows(IllegalStateException.class, () ->
                controller.createApiKey(request, body));
    }

    @Test
    @DisplayName("POST /api-keys：expireDays 为 null 时 expireAt 为 null")
    void createApiKeyWithNullExpireDays() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey apiKey = createApiKey("ak_live_new002", "merchant-1", "PAYMENTS");
        apiKey.setExpireAt(null);
        ApiKeyService.CreateApiKeyResult result =
                new ApiKeyService.CreateApiKeyResult(apiKey, "plain-secret");

        when(apiKeyService.createApiKey(eq("merchant-1"), eq("PAYMENTS"),
                isNull(), isNull())).thenReturn(result);

        ApiKeyController.CreateApiKeyRequest body = new ApiKeyController.CreateApiKeyRequest();
        body.setScopes("PAYMENTS");
        body.setExpireDays(null);

        ResponseEntity<Map<String, Object>> response =
                controller.createApiKey(request, body);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNull(response.getBody().get("expireAt"));
    }

    // === 列出 ===

    @Test
    @DisplayName("GET /api-keys：列出当前租户的所有 Key")
    void listApiKeysReturnsAllKeys() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey key1 = createApiKey("ak_live_k001", "merchant-1", "PAYMENTS");
        ApiKey key2 = createApiKey("ak_live_k002", "merchant-1", "REFUNDS");
        when(apiKeyService.listApiKeys("merchant-1")).thenReturn(List.of(key2, key1));

        ResponseEntity<List<Map<String, Object>>> response =
                controller.listApiKeys(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
        // 摘要 Map 不含密钥
        assertNull(response.getBody().get(0).get("keySecret"));
    }

    // === 详情 ===

    @Test
    @DisplayName("GET /api-keys/{keyId}：存在时返回详情")
    void getApiKeyReturnsDetailWhenFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey key = createApiKey("ak_live_det001", "merchant-1", "PAYMENTS");
        when(apiKeyService.listApiKeys("merchant-1")).thenReturn(List.of(key));

        ResponseEntity<Map<String, Object>> response =
                controller.getApiKey(request, "ak_live_det001");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ak_live_det001", response.getBody().get("keyId"));
        assertEquals("PAYMENTS", response.getBody().get("scopes"));
        // 详情 Map 不含密钥哈希
        assertNull(response.getBody().get("keySecret"));
    }

    @Test
    @DisplayName("GET /api-keys/{keyId}：不存在时返回 404")
    void getApiKeyReturns404WhenNotFound() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        when(apiKeyService.listApiKeys("merchant-1")).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response =
                controller.getApiKey(request, "nonexistent");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // === 轮换 ===

    @Test
    @DisplayName("POST /api-keys/{keyId}/rotate：轮换成功返回 201 + 新密钥")
    void rotateApiKeyReturns201WithNewSecret() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey newKey = createApiKey("ak_live_newrot", "merchant-1", "PAYMENTS");
        newKey.setRotatedFromId("ak_live_oldrot");
        ApiKeyService.CreateApiKeyResult result =
                new ApiKeyService.CreateApiKeyResult(newKey, "new-plain-secret");

        when(apiKeyService.rotateApiKey("ak_live_oldrot")).thenReturn(result);

        ResponseEntity<Map<String, Object>> response =
                controller.rotateApiKey(request, "ak_live_oldrot");

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ak_live_newrot", response.getBody().get("keyId"));
        assertEquals("new-plain-secret", response.getBody().get("keySecret"));
        assertEquals("ak_live_oldrot", response.getBody().get("rotatedFromId"));
    }

    // === 撤销 ===

    @Test
    @DisplayName("POST /api-keys/{keyId}/revoke：撤销成功返回 200")
    void revokeApiKeyReturns200() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey revokedKey = createApiKey("ak_live_rev001", "merchant-1", "PAYMENTS");
        revokedKey.setStatus(ApiKeyStatus.REVOKED);
        revokedKey.setRevokedReason("安全泄露");
        revokedKey.setRevokedAt(LocalDateTime.now());

        when(apiKeyService.revokeApiKey("ak_live_rev001", "安全泄露")).thenReturn(revokedKey);

        ApiKeyController.RevokeApiKeyRequest body = new ApiKeyController.RevokeApiKeyRequest();
        body.setReason("安全泄露");

        ResponseEntity<Map<String, Object>> response =
                controller.revokeApiKey(request, "ak_live_rev001", body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("REVOKED", response.getBody().get("status"));
        assertEquals("安全泄露", response.getBody().get("revokedReason"));
    }

    @Test
    @DisplayName("POST /api-keys/{keyId}/revoke：无请求体时 reason 为 null")
    void revokeApiKeyWithNullBody() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("nexus.tenantId", "merchant-1");

        ApiKey revokedKey = createApiKey("ak_live_rev002", "merchant-1", "PAYMENTS");
        revokedKey.setStatus(ApiKeyStatus.REVOKED);

        when(apiKeyService.revokeApiKey("ak_live_rev002", null)).thenReturn(revokedKey);

        ResponseEntity<Map<String, Object>> response =
                controller.revokeApiKey(request, "ak_live_rev002", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("REVOKED", response.getBody().get("status"));
    }

    // === 辅助方法 ===

    private ApiKey createApiKey(String keyId, String merchantId, String scopes) {
        ApiKey key = new ApiKey();
        key.setId(System.nanoTime());
        key.setKeyId(keyId);
        key.setKeySecret("hashed-secret-value-64-chars-a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4");
        key.setMerchantId(merchantId);
        key.setScopes(scopes);
        key.setStatus(ApiKeyStatus.ACTIVE);
        key.setCreatedAt(LocalDateTime.now());
        key.setExpireAt(LocalDateTime.now().plusDays(90));
        key.setDescription("测试 Key");
        return key;
    }
}