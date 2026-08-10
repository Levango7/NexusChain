package org.nexus.sdk.v2.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.v2.V2ApiException;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TenantClient} 单元测试（P4-T9）。
 *
 * <p>测试请求构造（含 PATCH 方法）、X-Tenant-Api-Key 头注入、
 * 查询字符串构造、参数校验与错误处理。</p>
 */
@DisplayName("TenantClient 多租户管理客户端")
class TenantClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_KEY = "test-api-key";
    private static final String TENANT_API_KEY = "test-tenant-api-key";

    @Test
    @DisplayName("构造 - 去除尾部斜杠")
    void constructor_stripsTrailingSlash() {
        TenantClient client = new TenantClient("http://localhost:8080/", API_KEY);
        assertEquals("http://localhost:8080", client.baseUrl());
    }

    @Test
    @DisplayName("构造 - 仅商户 API Key")
    void constructor_onlyApiKey() {
        TenantClient client = new TenantClient(BASE_URL, API_KEY);
        assertEquals(API_KEY, client.apiKey());
        assertNull(client.tenantApiKey());
    }

    @Test
    @DisplayName("构造 - 含租户 API Key")
    void constructor_withTenantApiKey() {
        TenantClient client = new TenantClient(BASE_URL, API_KEY, TENANT_API_KEY);
        assertEquals(API_KEY, client.apiKey());
        assertEquals(TENANT_API_KEY, client.tenantApiKey());
    }

    @Nested
    @DisplayName("头注入 - X-Tenant-Api-Key")
    class TenantApiKeyHeaderTests {

        @Test
        @DisplayName("同时注入商户与租户 API Key 头")
        void bothHeadersInjected() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY, TENANT_API_KEY);
            HttpRequest req = client.buildGetRequest("/api/v2/tenants/t-1");

            assertEquals(API_KEY, firstHeader(req, TenantClient.API_KEY_HEADER).orElse(null));
            assertEquals(TENANT_API_KEY, firstHeader(req, TenantClient.TENANT_API_KEY_HEADER).orElse(null));
        }

        @Test
        @DisplayName("仅租户 API Key 时只注入 X-Tenant-Api-Key")
        void onlyTenantHeader() {
            TenantClient client = new TenantClient(BASE_URL, null, TENANT_API_KEY);
            HttpRequest req = client.buildGetRequest("/api/v2/tenants/t-1");

            assertFalse(firstHeader(req, TenantClient.API_KEY_HEADER).isPresent());
            assertEquals(TENANT_API_KEY, firstHeader(req, TenantClient.TENANT_API_KEY_HEADER).orElse(null));
        }

        @Test
        @DisplayName("仅商户 API Key 时只注入 X-NexusChain-ApiKey")
        void onlyMerchantHeader() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildGetRequest("/api/v2/tenants/t-1");

            assertEquals(API_KEY, firstHeader(req, TenantClient.API_KEY_HEADER).orElse(null));
            assertFalse(firstHeader(req, TenantClient.TENANT_API_KEY_HEADER).isPresent());
        }

        @Test
        @DisplayName("两个 Key 都为 null 时不注入认证头")
        void noAuthHeaders() {
            TenantClient client = new TenantClient(BASE_URL, null, null);
            HttpRequest req = client.buildGetRequest("/api/v2/tenants/t-1");

            assertFalse(firstHeader(req, TenantClient.API_KEY_HEADER).isPresent());
            assertFalse(firstHeader(req, TenantClient.TENANT_API_KEY_HEADER).isPresent());
        }
    }

    @Nested
    @DisplayName("请求构造 - createTenant")
    class CreateTenantRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/tenants", "{}");
            assertEquals(BASE_URL + "/api/v2/tenants", req.uri().toString());
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("必要头注入")
        void requiredHeaders() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/tenants", "{}");

            assertEquals("application/json", firstHeader(req, "Content-Type").orElse(null));
            assertEquals("2", firstHeader(req, TenantClient.VERSION_HEADER).orElse(null));
        }
    }

    @Nested
    @DisplayName("请求构造 - updateTenant (PATCH)")
    class UpdateTenantRequestTests {

        @Test
        @DisplayName("method 为 PATCH")
        void methodIsPatch() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            String id = "t-123";
            HttpRequest req = client.buildPatchRequest("/api/v2/tenants/" + id, "{}");
            assertEquals("PATCH", req.method());
            assertTrue(req.uri().toString().endsWith("/tenants/" + id));
        }
    }

    @Nested
    @DisplayName("请求构造 - suspendTenant / reactivateTenant")
    class SuspendReactivateRequestTests {

        @Test
        @DisplayName("suspend URL 包含 /suspend 后缀")
        void suspendUrl() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            String id = "t-123";
            HttpRequest req = client.buildPostRequest("/api/v2/tenants/" + id + "/suspend", "{}");
            assertTrue(req.uri().toString().endsWith("/tenants/" + id + "/suspend"));
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("reactivate URL 包含 /reactivate 后缀")
        void reactivateUrl() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            String id = "t-123";
            HttpRequest req = client.buildPostRequest("/api/v2/tenants/" + id + "/reactivate", "{}");
            assertTrue(req.uri().toString().endsWith("/tenants/" + id + "/reactivate"));
            assertEquals("POST", req.method());
        }
    }

    @Nested
    @DisplayName("请求构造 - getUsage / getRateLimitStatus")
    class GetUsageAndRateLimitTests {

        @Test
        @DisplayName("getUsage 查询字符串包含 periodStart 与 periodEnd")
        void getUsage_query() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic(
                    "periodStart", "2026-01-01",
                    "periodEnd", "2026-02-01"
            );
            assertTrue(query.contains("periodStart=2026-01-01"));
            assertTrue(query.contains("periodEnd=2026-02-01"));
        }

        @Test
        @DisplayName("getRateLimitStatus URL 正确")
        void getRateLimitStatus_url() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            String id = "t-123";
            HttpRequest req = client.buildGetRequest("/api/v2/tenants/" + id + "/rate-limit-status");
            assertTrue(req.uri().toString().endsWith("/tenants/" + id + "/rate-limit-status"));
            assertEquals("GET", req.method());
        }
    }

    @Nested
    @DisplayName("参数校验")
    class ParameterValidationTests {

        @Test
        @DisplayName("createTenant - null name → NPE")
        void createTenant_nullName() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createTenant(null, "admin@a.com", "pro"));
        }

        @Test
        @DisplayName("createTenant - null adminEmail → NPE")
        void createTenant_nullEmail() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createTenant("Acme", null, "pro"));
        }

        @Test
        @DisplayName("createTenant - null plan → NPE")
        void createTenant_nullPlan() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createTenant("Acme", "admin@a.com", null));
        }

        @Test
        @DisplayName("getTenant - null id → NPE")
        void getTenant_nullId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getTenant(null));
        }

        @Test
        @DisplayName("getTenant - 空 id → V2ApiException")
        void getTenant_emptyId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () -> client.getTenant(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("updateTenant - null id → NPE")
        void updateTenant_nullId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            Map<String, Object> updates = new HashMap<>();
            updates.put("name", "New Name");
            assertThrows(NullPointerException.class, () -> client.updateTenant(null, updates));
        }

        @Test
        @DisplayName("updateTenant - null updates → NPE")
        void updateTenant_nullUpdates() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.updateTenant("t-1", null));
        }

        @Test
        @DisplayName("updateTenant - 空 updates → V2ApiException")
        void updateTenant_emptyUpdates() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.updateTenant("t-1", new HashMap<>()));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("suspendTenant - null id → NPE")
        void suspendTenant_nullId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.suspendTenant(null, "reason"));
        }

        @Test
        @DisplayName("suspendTenant - 空 id → V2ApiException")
        void suspendTenant_emptyId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.suspendTenant("", "reason"));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("reactivateTenant - null id → NPE")
        void reactivateTenant_nullId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.reactivateTenant(null));
        }

        @Test
        @DisplayName("getUsage - null periodStart → NPE")
        void getUsage_nullPeriodStart() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.getUsage("t-1", null, "2026-02-01"));
        }

        @Test
        @DisplayName("getRateLimitStatus - null id → NPE")
        void getRateLimitStatus_nullId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getRateLimitStatus(null));
        }

        @Test
        @DisplayName("getRateLimitStatus - 空 id → V2ApiException")
        void getRateLimitStatus_emptyId() {
            TenantClient client = new TenantClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.getRateLimitStatus(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }
    }

    private static Optional<String> firstHeader(HttpRequest req, String name) {
        return req.headers().firstValue(name);
    }
}