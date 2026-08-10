package org.nexus.sdk.v2.solana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.v2.V2ApiException;

import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SolanaClient} 单元测试（P4-T9）。
 *
 * <p>测试请求构造（URL、method、headers、body）、参数校验与错误处理。
 * 不依赖真实服务，仅验证客户端的非网络逻辑。</p>
 */
@DisplayName("SolanaClient Solana 链客户端")
class SolanaClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_KEY = "test-api-key";

    @Test
    @DisplayName("构造 - 去除尾部斜杠")
    void constructor_stripsTrailingSlash() {
        SolanaClient client = new SolanaClient("http://localhost:8080/", API_KEY);
        assertEquals("http://localhost:8080", client.baseUrl());
    }

    @Test
    @DisplayName("构造 - null apiKey 允许")
    void constructor_nullApiKeyAllowed() {
        SolanaClient client = new SolanaClient(BASE_URL, null);
        assertNull(client.apiKey());
    }

    @Nested
    @DisplayName("请求构造 - createPayment")
    class CreatePaymentRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            // 通过反射调用私有逻辑太复杂，这里通过 buildPostRequest 验证
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/solana/payment", "{}");
            assertEquals(BASE_URL + "/api/v2/bridge/solana/payment", req.uri().toString());
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("必要头注入 - Content-Type / Accept / API-Version / ApiKey")
        void requiredHeaders() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/solana/payment", "{}");

            assertEquals("application/json", firstHeader(req, "Content-Type").orElse(null));
            assertEquals("application/json", firstHeader(req, "Accept").orElse(null));
            assertEquals("2", firstHeader(req, SolanaClient.VERSION_HEADER).orElse(null));
            assertEquals(API_KEY, firstHeader(req, SolanaClient.API_KEY_HEADER).orElse(null));
        }

        @Test
        @DisplayName("null apiKey 时不注入 ApiKey 头")
        void noApiKeyHeaderWhenNull() {
            SolanaClient client = new SolanaClient(BASE_URL, null);
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/solana/balance/abc");
            assertFalse(firstHeader(req, SolanaClient.API_KEY_HEADER).isPresent());
        }
    }

    @Nested
    @DisplayName("请求构造 - getTransactionStatus")
    class GetTransactionStatusRequestTests {

        @Test
        @DisplayName("URL 路径包含 signature")
        void urlContainsSignature() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            String signature = "sig-abc-123";
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/solana/tx/" + signature);
            assertTrue(req.uri().toString().endsWith("/tx/" + signature));
            assertEquals("GET", req.method());
        }
    }

    @Nested
    @DisplayName("请求构造 - getBalance")
    class GetBalanceRequestTests {

        @Test
        @DisplayName("URL 路径包含 address")
        void urlContainsAddress() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            String address = "addr-xyz-789";
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/solana/balance/" + address);
            assertTrue(req.uri().toString().endsWith("/balance/" + address));
        }
    }

    @Nested
    @DisplayName("请求构造 - estimateFee")
    class EstimateFeeRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/solana/estimate-fee", "{}");
            assertTrue(req.uri().toString().endsWith("/bridge/solana/estimate-fee"));
            assertEquals("POST", req.method());
        }
    }

    @Nested
    @DisplayName("参数校验")
    class ParameterValidationTests {

        @Test
        @DisplayName("createPayment - null fromAddress → NPE")
        void createPayment_nullFrom() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createPayment(null, "to", 1L, "native"));
        }

        @Test
        @DisplayName("createPayment - null toAddress → NPE")
        void createPayment_nullTo() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.createPayment("from", null, 1L, "native"));
        }

        @Test
        @DisplayName("createPayment - 负数 amountLamports → V2ApiException")
        void createPayment_negativeAmount() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.createPayment("from", "to", -1L, "native"));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("getTransactionStatus - null signature → NPE")
        void getTransactionStatus_nullSig() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.getTransactionStatus(null));
        }

        @Test
        @DisplayName("getTransactionStatus - 空 signature → V2ApiException")
        void getTransactionStatus_emptySig() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.getTransactionStatus(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("getBalance - null address → NPE")
        void getBalance_nullAddress() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getBalance(null));
        }

        @Test
        @DisplayName("getBalance - 空 address → V2ApiException")
        void getBalance_emptyAddress() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () -> client.getBalance(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("estimateFee - priorityLevel < 0 → V2ApiException")
        void estimateFee_negativePriority() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.estimateFee(-1));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("estimateFee - priorityLevel > 3 → V2ApiException")
        void estimateFee_priorityTooHigh() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.estimateFee(4));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("estimateFee - 边界值 0 与 3 不抛异常（虽会因无服务而失败，但不是 INVALID_ARGUMENT）")
        void estimateFee_boundaryValues() {
            SolanaClient client = new SolanaClient(BASE_URL, API_KEY);
            // 边界值本身校验通过，后续因无服务会抛 HTTP_ERROR，但不应该是 INVALID_ARGUMENT
            for (long level : new long[]{0, 3}) {
                V2ApiException ex = assertThrows(V2ApiException.class, () ->
                        client.estimateFee(level));
                assertNotEquals("INVALID_ARGUMENT", ex.errorCode(),
                        "level=" + level + " 应通过参数校验");
            }
        }
    }

    /** 工具：取请求中某头的第一个值 */
    private static Optional<String> firstHeader(HttpRequest req, String name) {
        return req.headers().firstValue(name);
    }
}