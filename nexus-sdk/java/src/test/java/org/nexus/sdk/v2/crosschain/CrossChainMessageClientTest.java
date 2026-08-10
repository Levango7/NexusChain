package org.nexus.sdk.v2.crosschain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.nexus.sdk.v2.V2ApiException;

import java.net.http.HttpRequest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CrossChainMessageClient} 单元测试（P4-T9）。
 *
 * <p>测试请求构造、查询字符串构造、参数校验与错误处理。</p>
 */
@DisplayName("CrossChainMessageClient 跨链消息客户端")
class CrossChainMessageClientTest {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String API_KEY = "test-api-key";

    @Test
    @DisplayName("构造 - 去除尾部斜杠")
    void constructor_stripsTrailingSlash() {
        CrossChainMessageClient client = new CrossChainMessageClient("http://localhost:8080/", API_KEY);
        assertEquals("http://localhost:8080", client.baseUrl());
    }

    @Nested
    @DisplayName("请求构造 - sendMessage")
    class SendMessageRequestTests {

        @Test
        @DisplayName("URL 与 method 正确")
        void urlAndMethod() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/messages", "{}");
            assertEquals(BASE_URL + "/api/v2/bridge/messages", req.uri().toString());
            assertEquals("POST", req.method());
        }

        @Test
        @DisplayName("必要头注入")
        void requiredHeaders() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/messages", "{}");

            assertEquals("application/json", firstHeader(req, "Content-Type").orElse(null));
            assertEquals("2", firstHeader(req, CrossChainMessageClient.VERSION_HEADER).orElse(null));
            assertEquals(API_KEY, firstHeader(req, CrossChainMessageClient.API_KEY_HEADER).orElse(null));
        }
    }

    @Nested
    @DisplayName("请求构造 - getMessageStatus / getMessageDetails")
    class GetMessageRequestTests {

        @Test
        @DisplayName("getMessageStatus URL 包含 /status 后缀")
        void getMessageStatus_url() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            String id = "msg-001";
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/messages/" + id + "/status");
            assertTrue(req.uri().toString().endsWith("/messages/" + id + "/status"));
            assertEquals("GET", req.method());
        }

        @Test
        @DisplayName("getMessageDetails URL 不含 /status 后缀")
        void getMessageDetails_url() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            String id = "msg-002";
            HttpRequest req = client.buildGetRequest("/api/v2/bridge/messages/" + id);
            assertTrue(req.uri().toString().endsWith("/messages/" + id));
            assertFalse(req.uri().toString().contains("/status"));
        }
    }

    @Nested
    @DisplayName("请求构造 - listMessages")
    class ListMessagesRequestTests {

        @Test
        @DisplayName("查询字符串构造 - 全参数")
        void query_allParams() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic(
                    "sourceChain", "ETH",
                    "targetChain", "BSC",
                    "cursor", "abc",
                    "pageSize", "20"
            );
            assertTrue(query.contains("sourceChain=ETH"));
            assertTrue(query.contains("targetChain=BSC"));
            assertTrue(query.contains("cursor=abc"));
            assertTrue(query.contains("pageSize=20"));
            assertTrue(query.startsWith("?"));
        }

        @Test
        @DisplayName("查询字符串构造 - null 值跳过")
        void query_nullSkipped() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic(
                    "sourceChain", null,
                    "targetChain", "BSC",
                    "cursor", null,
                    "pageSize", null
            );
            assertFalse(query.contains("sourceChain"));
            assertFalse(query.contains("cursor"));
            assertFalse(query.contains("pageSize"));
            assertTrue(query.contains("targetChain=BSC"));
        }

        @Test
        @DisplayName("查询字符串构造 - 全 null 返回空串")
        void query_allNull_returnsEmpty() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            String query = client.buildQueryPublic("a", null, "b", null);
            assertEquals("", query);
        }
    }

    @Nested
    @DisplayName("请求构造 - retryMessage")
    class RetryMessageRequestTests {

        @Test
        @DisplayName("URL 包含 /retry 后缀")
        void urlContainsRetry() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            String id = "msg-fail-001";
            HttpRequest req = client.buildPostRequest("/api/v2/bridge/messages/" + id + "/retry", "{}");
            assertTrue(req.uri().toString().endsWith("/messages/" + id + "/retry"));
            assertEquals("POST", req.method());
        }
    }

    @Nested
    @DisplayName("参数校验")
    class ParameterValidationTests {

        @Test
        @DisplayName("sendMessage - null sourceChain → NPE")
        void sendMessage_nullSource() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.sendMessage(null, "BSC", "0x", "payload", "RAW"));
        }

        @Test
        @DisplayName("sendMessage - null payload → NPE")
        void sendMessage_nullPayload() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () ->
                    client.sendMessage("ETH", "BSC", "0x", null, "RAW"));
        }

        @Test
        @DisplayName("sendMessage - 非法 format → V2ApiException")
        void sendMessage_invalidFormat() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.sendMessage("ETH", "BSC", "0x", "payload", "XML"));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
            assertTrue(ex.getMessage().contains("RAW/PROTOBUF/JSON"));
        }

        @Test
        @DisplayName("sendMessage - 合法 format 不抛 INVALID_ARGUMENT（RAW/PROTOBUF/JSON）")
        void sendMessage_validFormats() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            for (String fmt : new String[]{"RAW", "PROTOBUF", "JSON"}) {
                V2ApiException ex = assertThrows(V2ApiException.class, () ->
                        client.sendMessage("ETH", "BSC", "0x", "payload", fmt));
                assertNotEquals("INVALID_ARGUMENT", ex.errorCode(),
                        "format=" + fmt + " 应通过校验");
            }
        }

        @Test
        @DisplayName("getMessageStatus - null messageId → NPE")
        void getMessageStatus_nullId() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getMessageStatus(null));
        }

        @Test
        @DisplayName("getMessageStatus - 空 messageId → V2ApiException")
        void getMessageStatus_emptyId() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.getMessageStatus(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }

        @Test
        @DisplayName("getMessageDetails - null messageId → NPE")
        void getMessageDetails_nullId() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.getMessageDetails(null));
        }

        @Test
        @DisplayName("retryMessage - null messageId → NPE")
        void retryMessage_nullId() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            assertThrows(NullPointerException.class, () -> client.retryMessage(null));
        }

        @Test
        @DisplayName("retryMessage - 空 messageId → V2ApiException")
        void retryMessage_emptyId() {
            CrossChainMessageClient client = new CrossChainMessageClient(BASE_URL, API_KEY);
            V2ApiException ex = assertThrows(V2ApiException.class, () ->
                    client.retryMessage(""));
            assertEquals("INVALID_ARGUMENT", ex.errorCode());
        }
    }

    private static Optional<String> firstHeader(HttpRequest req, String name) {
        return req.headers().firstValue(name);
    }
}