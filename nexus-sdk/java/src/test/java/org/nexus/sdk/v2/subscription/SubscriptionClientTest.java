package org.nexus.sdk.v2.subscription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.v2.V2ApiException;

import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SubscriptionClient} 单元测试（P4-T9）。
 *
 * <p>测试请求构造（含 DELETE 方法）、查询字符串构造、参数校验与错误处理。</p>
 */
@DisplayName("SubscriptionClient 订阅管理客户端")
class SubscriptionClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_KEY = "test-api-key";

    @Test
    @DisplayName("构造 - 去除尾部斜杠")
    void constructor_stripsTrailingSlash() {
        SubscriptionClient client = new SubscriptionClient("http://localhost:8080/", API_KEY);
        assertEquals("http://localhost:8080", client.baseUrl());
    }

    @Nested
    @DisplayName("请求构造 - createSubscription")
    class CreateSubscriptionRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/subscriptions", "{}");
            assertEquals(BASE_URL + "/api/v2/subscriptions", req.uri().toString());
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("必要头注入")
        void requiredHeaders() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/subscriptions", "{}");

            assertEquals("application/json", firstHeader(req, "Content-Type").orElse(null));
            assertEquals("2", firstHeader(req, SubscriptionClient.VERSION_HEADER).orElse(null));
            assertEquals(API_KEY, firstHeader(req, SubscriptionClient.API_KEY_HEADER).orElse(null));
        }
    }

    @Nested
    @DisplayName("请求构造 - getSubscription")
    class GetSubscriptionRequestTests {

        @Test
        @DisplayName("URL 包含 subscriptionId")
        void urlContainsId() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String id = "sub-123";
            HttpRequest req = client.buildGetRequest("/api/v2/subscriptions/" + id);
            assertTrue(req.uri().toString().endsWith("/subscriptions/" + id));
            assertEquals("GET", req.method());
        }
    }

    @Nested
    @DisplayName("请求构造 - cancelSubscription (DELETE)")
    class CancelSubscriptionRequestTests {

        @Test
        @DisplayName("method 为 DELETE")
        void methodIsDelete() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String id = "sub-123";
            HttpRequest req = client.buildDeleteRequest("/api/v2/subscriptions/" + id);
            assertEquals("DELETE", req.method());
            assertTrue(req.uri().toString().endsWith("/subscriptions/" + id));
        }

        @Test
        @DisplayName("查询字符串包含 reason")
        void queryContainsReason() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic("reason", "Customer request");
            assertTrue(query.contains("reason=Customer+request")
                    || query.contains("reason=Customer request"));
        }

        @Test
        @DisplayName("查询字符串 - null reason 跳过")
        void queryNullReasonSkipped() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic("reason", null);
            assertEquals("", query);
        }
    }

    @Nested
    @DisplayName("请求构造 - upgradeSubscription / downgradeSubscription")
    class ChangePlanRequestTests {

        @Test
        @DisplayName("upgrade URL 包含 /upgrade 后缀")
        void upgradeUrl() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String id = "sub-123";
            HttpRequest req = client.buildPostRequest(
                    "/api/v2/subscriptions/" + id + "/upgrade", "{}");
            assertTrue(req.uri().toString().endsWith("/subscriptions/" + id + "/upgrade"));
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("downgrade URL 包含 /downgrade 后缀")
        void downgradeUrl() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String id = "sub-123";
            HttpRequest req = client.buildPostRequest(
                    "/api/v2/subscriptions/" + id + "/downgrade", "{}");
            assertTrue(req.uri().toString().endsWith("/subscriptions/" + id + "/downgrade"));
            assertEquals("POST", req.method());
        }
    }

    @Nested
    @DisplayName("请求构造 - listPlans")
    class ListPlansRequestTests {

        @Test
        @DisplayName("URL 正确")
        void url() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildGetRequest("/api/v2/subscriptions/plans");
            assertTrue(req.uri().toString().endsWith("/subscriptions/plans"));
            assertEquals("GET", req.method());
        }
    }

    @Nested
    @DisplayName("请求构造 - getUsage")
    class GetUsageRequestTests {

        @Test
        @DisplayName("查询字符串包含 periodStart 与 periodEnd")
        void queryContainsPeriod() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic(
                    "periodStart", "2026-01-01",
                    "periodEnd", "2026-02-01"
            );
            assertTrue(query.contains("periodStart=2026-01-01"));
            assertTrue(query.contains("periodEnd=2026-02-01"));
        }
    }

    @Nested
    @DisplayName("参数校验")
    class ParameterValidationTests {

        @Test
        @DisplayName("createSubscription - null planId → NPE")
        void createSubscription_nullPlan() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createSubscription(null, "cust", "token"));
        }

        @Test
        @DisplayName("createSubscription - null customerId → NPE")
        void createSubscription_nullCustomer() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createSubscription("plan", null, "token"));
        }

        @Test
        @DisplayName("createSubscription - null paymentMethodToken → NPE")
        void createSubscription_nullToken() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createSubscription("plan", "cust", null));
        }

        @Test
        @DisplayName("getSubscription - null id → NPE")
        void getSubscription_nullId() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getSubscription(null));
        }

        @Test
        @DisplayName("getSubscription - 空 id → V2ApiException")
        void getSubscription_emptyId() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.getSubscription(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("cancelSubscription - null id → NPE")
        void cancelSubscription_nullId() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.cancelSubscription(null, "reason"));
        }

        @Test
        @DisplayName("upgradeSubscription - null id → NPE")
        void upgradeSubscription_nullId() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.upgradeSubscription(null, "newPlan"));
        }

        @Test
        @DisplayName("upgradeSubscription - null newPlanId → NPE")
        void upgradeSubscription_nullNewPlan() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.upgradeSubscription("sub", null));
        }

        @Test
        @DisplayName("downgradeSubscription - null id → NPE")
        void downgradeSubscription_nullId() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.downgradeSubscription(null, "newPlan"));
        }

        @Test
        @DisplayName("getUsage - null periodStart → NPE")
        void getUsage_nullPeriodStart() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.getUsage("sub", null, "2026-02-01"));
        }

        @Test
        @DisplayName("getUsage - null periodEnd → NPE")
        void getUsage_nullPeriodEnd() {
            SubscriptionClient client = new SubscriptionClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.getUsage("sub", "2026-01-01", null));
        }
    }

    private static Optional<String> firstHeader(HttpRequest req, String name) {
        return req.headers().firstValue(name);
    }
}