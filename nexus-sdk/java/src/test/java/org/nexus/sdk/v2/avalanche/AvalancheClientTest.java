package org.nexus.sdk.v2.avalanche;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.v2.V2ApiException;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AvalancheClient} 单元测试（P4-T9）。
 *
 * <p>测试请求构造、参数校验与错误处理。</p>
 */
@DisplayName("AvalancheClient Avalanche C-Chain 客户端")
class AvalancheClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_KEY = "test-api-key";

    @Test
    @DisplayName("构造 - 去除尾部斜杠")
    void constructor_stripsTrailingSlash() {
        AvalancheClient client = new AvalancheClient("http://localhost:8080/", API_KEY);
        assertEquals("http://localhost:8080", client.baseUrl());
    }

    @Test
    @DisplayName("构造 - null apiKey 允许")
    void constructor_nullApiKeyAllowed() {
        AvalancheClient client = new AvalancheClient(BASE_URL, null);
        assertNull(client.apiKey());
    }

    @Nested
    @DisplayName("请求构造 - createPayment")
    class CreatePaymentRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/avalanche/payment", "{}");
            assertEquals(BASE_URL + "/api/v2/bridge/avalanche/payment", req.uri().toString());
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("必要头注入")
        void requiredHeaders() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/avalanche/payment", "{}");

            assertEquals("application/json", firstHeader(req, "Content-Type").orElse(null));
            assertEquals("application/json", firstHeader(req, "Accept").orElse(null));
            assertEquals("2", firstHeader(req, AvalancheClient.VERSION_HEADER).orElse(null));
            assertEquals(API_KEY, firstHeader(req, AvalancheClient.API_KEY_HEADER).orElse(null));
        }
    }

    @Nested
    @DisplayName("请求构造 - getTransactionStatus / getBalance")
    class GetRequestTests {

        @Test
        @DisplayName("getTransactionStatus URL 包含 txHash")
        void getTransactionStatus_url() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            String txHash = "0xabc123";
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/avalanche/tx/" + txHash);
            assertTrue(req.uri().toString().endsWith("/tx/" + txHash));
            assertEquals("GET", req.method());
        }

        @Test
        @DisplayName("getBalance URL 包含 address")
        void getBalance_url() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            String address = "0xdef456";
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/avalanche/balance/" + address);
            assertTrue(req.uri().toString().endsWith("/balance/" + address));
        }
    }

    @Nested
    @DisplayName("请求构造 - estimateGas")
    class EstimateGasRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/avalanche/estimate-gas", "{}");
            assertTrue(req.uri().toString().endsWith("/bridge/avalanche/estimate-gas"));
            assertEquals("POST", req.method());
        }
    }

    @Nested
    @DisplayName("参数校验")
    class ParameterValidationTests {

        @Test
        @DisplayName("createPayment - null fromAddress → NPE")
        void createPayment_nullFrom() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createPayment(null, "to", BigDecimal.ONE, "AVAX"));
        }

        @Test
        @DisplayName("createPayment - null amountAvax → NPE")
        void createPayment_nullAmount() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createPayment("from", "to", null, "AVAX"));
        }

        @Test
        @DisplayName("createPayment - null assetId → NPE")
        void createPayment_nullAssetId() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createPayment("from", "to", BigDecimal.ONE, null));
        }

        @Test
        @DisplayName("createPayment - 负数 amountAvax → V2ApiException")
        void createPayment_negativeAmount() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.createPayment("from", "to", new BigDecimal("-1"), "AVAX"));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("getTransactionStatus - null txHash → NPE")
        void getTransactionStatus_nullHash() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.getTransactionStatus(null));
        }

        @Test
        @DisplayName("getTransactionStatus - 空 txHash → V2ApiException")
        void getTransactionStatus_emptyHash() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.getTransactionStatus(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("getBalance - null address → NPE")
        void getBalance_nullAddress() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getBalance(null));
        }

        @Test
        @DisplayName("getBalance - 空 address → V2ApiException")
        void getBalance_emptyAddress() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () -> client.getBalance(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("estimateGas - 负数 gasUnits → V2ApiException")
        void estimateGas_negativeGasUnits() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.estimateGas(-1, BigDecimal.ONE));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("estimateGas - null gasPrice → NPE")
        void estimateGas_nullGasPrice() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.estimateGas(21000, null));
        }

        @Test
        @DisplayName("estimateGas - 负数 gasPrice → V2ApiException")
        void estimateGas_negativeGasPrice() {
            AvalancheClient client = new AvalancheClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.estimateGas(21000, new BigDecimal("-1")));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }
    }

    private static Optional<String> firstHeader(HttpRequest req, String name) {
        return req.headers().firstValue(name);
    }
}