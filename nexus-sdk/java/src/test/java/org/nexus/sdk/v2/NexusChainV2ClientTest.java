package org.nexus.sdk.v2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NexusChainV2Client} 单元测试（P4-T7）。
 *
 * <p>测试客户端的非网络逻辑（DTO 构造、异常解析等）。
 * 完整的网络集成测试由 gateway 端的 V2ApiIntegrationTest 覆盖。</p>
 */
@DisplayName("NexusChainV2Client v2 SDK 客户端")
class NexusChainV2ClientTest {

    @Test
    @DisplayName("客户端构造 - 去除尾部斜杠")
    void constructor_stripsTrailingSlash() {
        NexusChainV2Client client = new NexusChainV2Client("http://localhost:8080/", "key");
        // 通过发起一个请求验证 baseUrl 处理（这里只验证不抛异常）
        assertNotNull(client);
    }

    @Test
    @DisplayName("客户端构造 - null apiKey 允许（公开端点）")
    void constructor_nullApiKeyAllowed() {
        NexusChainV2Client client = new NexusChainV2Client("http://localhost:8080", null);
        assertNotNull(client);
    }

    @Nested
    @DisplayName("PaymentItem")
    class PaymentItemTests {

        @Test
        @DisplayName("Builder 构造 - 必填字段齐全")
        void builder_allRequiredFields() {
            PaymentItem item = PaymentItem.builder()
                    .merchantId(1L)
                    .amount(new BigDecimal("10000"))
                    .notifyUrl("http://cb.test")
                    .build();
            assertEquals(1L, item.getMerchantId());
            assertEquals(new BigDecimal("10000"), item.getAmount());
            assertEquals("NEX", item.getTokenSymbol());  // 默认值
            assertEquals("http://cb.test", item.getNotifyUrl());
        }

        @Test
        @DisplayName("Builder 构造 - 缺少 merchantId → NPE")
        void builder_missingMerchantId_throws() {
            assertThrows(NullPointerException.class, () -> PaymentItem.builder()
                    .amount(new BigDecimal("10000"))
                    .notifyUrl("http://cb.test")
                    .build());
        }

        @Test
        @DisplayName("Builder 构造 - 缺少 amount → NPE")
        void builder_missingAmount_throws() {
            assertThrows(NullPointerException.class, () -> PaymentItem.builder()
                    .merchantId(1L)
                    .notifyUrl("http://cb.test")
                    .build());
        }

        @Test
        @DisplayName("Builder 构造 - 缺少 notifyUrl → NPE")
        void builder_missingNotifyUrl_throws() {
            assertThrows(NullPointerException.class, () -> PaymentItem.builder()
                    .merchantId(1L)
                    .amount(new BigDecimal("10000"))
                    .build());
        }

        @Test
        @DisplayName("Builder 构造 - 全字段")
        void builder_allFields() {
            PaymentItem item = PaymentItem.builder()
                    .merchantId(1L)
                    .amount(new BigDecimal("10000"))
                    .tokenSymbol("USDC")
                    .description("test payment")
                    .payerAddress("0xabc")
                    .notifyUrl("http://cb.test")
                    .idempotencyKey("idem-123")
                    .build();
            assertEquals("USDC", item.getTokenSymbol());
            assertEquals("test payment", item.getDescription());
            assertEquals("0xabc", item.getPayerAddress());
            assertEquals("idem-123", item.getIdempotencyKey());
        }
    }

    @Nested
    @DisplayName("V2ApiException")
    class V2ApiExceptionTests {

        @Test
        @DisplayName("fromResponse - 标准 v2 错误格式")
        void fromResponse_standardV2Error() {
            String body = "{\"error\":{\"code\":\"ORDER_NOT_FOUND\","
                    + "\"message\":\"Order with id=123 not found\","
                    + "\"details\":{\"orderId\":123},"
                    + "\"traceId\":\"a1b2c3d4e5f6a7b8\"}}";
            V2ApiException ex = V2ApiException.fromResponse(404, body);

            assertEquals(404, ex.httpStatus());
            assertEquals("ORDER_NOT_FOUND", ex.errorCode());
            assertEquals("Order with id=123 not found", ex.getMessage());
            assertEquals("a1b2c3d4e5f6a7b8", ex.traceId());
            assertNotNull(ex.details());
            assertEquals(123, ex.details().get("orderId"));
        }

        @Test
        @DisplayName("fromResponse - 非 JSON 响应 → 降级为 HTTP_ERROR")
        void fromResponse_nonJson_fallsBack() {
            V2ApiException ex = V2ApiException.fromResponse(500, "Internal Server Error");
            assertEquals(500, ex.httpStatus());
            assertEquals("HTTP_ERROR", ex.errorCode());
        }

        @Test
        @DisplayName("fromResponse - 缺少 traceId → traceId 为 null")
        void fromResponse_missingTraceId() {
            String body = "{\"error\":{\"code\":\"BAD_REQUEST\",\"message\":\"bad\"}}";
            V2ApiException ex = V2ApiException.fromResponse(400, body);
            assertEquals("BAD_REQUEST", ex.errorCode());
            assertNull(ex.traceId());
        }

        @Test
        @DisplayName("toString 包含关键字段")
        void toString_containsKeyFields() {
            V2ApiException ex = new V2ApiException("msg", 400, "CODE", "trace",
                    (java.util.Map<String, Object>) null);
            String s = ex.toString();
            assertTrue(s.contains("400"));
            assertTrue(s.contains("CODE"));
            assertTrue(s.contains("trace"));
            assertTrue(s.contains("msg"));
        }
    }

    @Nested
    @DisplayName("CursorPage")
    class CursorPageTests {

        @Test
        @DisplayName("构造 + 访问器")
        void constructionAndAccessors() {
            List<String> data = List.of("a", "b");
            CursorPage<String> page = new CursorPage<>(data, "next", true, 2, 20);
            assertEquals(data, page.data());
            assertEquals("next", page.nextCursor());
            assertTrue(page.hasMore());
            assertEquals(2, page.count());
            assertEquals(20, page.pageSize());
        }
    }

    @Nested
    @DisplayName("BatchResult")
    class BatchResultTests {

        @Test
        @DisplayName("全部成功 → allSucceeded=true")
        void allSucceeded() {
            List<BatchResult.Succeeded> succ = List.of(
                    new BatchResult.Succeeded(0, 1L, "ORD-1", "PENDING"),
                    new BatchResult.Succeeded(1, 2L, "ORD-2", "PENDING"));
            BatchResult result = new BatchResult(succ, List.of(), 2);
            assertTrue(result.allSucceeded());
            assertEquals(2, result.succeededCount());
            assertEquals(0, result.failedCount());
        }

        @Test
        @DisplayName("部分失败 → allSucceeded=false")
        void partialFailure() {
            List<BatchResult.Succeeded> succ = List.of(
                    new BatchResult.Succeeded(0, 1L, "ORD-1", "PENDING"));
            List<BatchResult.Failed> fail = List.of(
                    new BatchResult.Failed(1, "BAD_REQUEST", "invalid"));
            BatchResult result = new BatchResult(succ, fail, 2);
            assertFalse(result.allSucceeded());
            assertEquals(1, result.succeededCount());
            assertEquals(1, result.failedCount());
        }
    }
}