package org.nexus.gateway.apiversion;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * v2 API 集成测试（P4-T7）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>v1 端点响应包含 Deprecation/Sunset/Link 头</li>
 *   <li>v2 端点响应不包含弃用头</li>
 *   <li>v2 订单创建 + 游标分页列表 + 字段筛选</li>
 *   <li>v2 批量支付</li>
 *   <li>v2 统一错误响应格式</li>
 *   <li>版本协商头</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@WithMockUser(username = "admin", roles = {"ADMIN", "OPERATOR"})
@DisplayName("v2 API 集成测试")
class V2ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String apiKey;
    private static Long merchantId;
    private static Long order1Id;
    private static Long order2Id;

    @Test
    @Order(1)
    @DisplayName("v1 端点响应包含 Deprecation/Sunset/Link 头")
    void v1Endpoint_hasDeprecationHeaders() throws Exception {
        mockMvc.perform(post("/api/v1/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"V2TestShop\",\"email\":\"v2@test.com\","
                                + "\"settlementAddress\":\"1V2Addr000000000000000000000000000000\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Deprecation"))
                .andExpect(header().string("Deprecation", "2026-08-09"))
                .andExpect(header().exists("Sunset"))
                .andExpect(header().string("Sunset", "2027-02-09"))
                .andExpect(header().exists("Link"))
                .andDo(result -> {
                    String link = result.getResponse().getHeader("Link");
                    assertNotNull(link);
                    assertTrue(link.contains("deprecation"));
                });
    }

    @Test
    @Order(2)
    @DisplayName("v1 端点响应包含 X-NexusChain-API-Version=1 头")
    void v1Endpoint_hasVersionHeader() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/1"))
                .andExpect(header().string("X-NexusChain-API-Version", "1"));
    }

    @Test
    @Order(3)
    @DisplayName("v2 端点响应不包含 Deprecation 头")
    void v2Endpoint_noDeprecationHeaders() throws Exception {
        mockMvc.perform(post("/api/v2/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"V2TestShop2\",\"email\":\"v2b@test.com\","
                                + "\"settlementAddress\":\"1V2BAddr00000000000000000000000000000\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Deprecation"))
                .andExpect(header().doesNotExist("Sunset"))
                .andExpect(header().string("X-NexusChain-API-Version", "2"));
    }

    @Test
    @Order(4)
    @DisplayName("v2 商户注册 + 验证 + 生成 API Key")
    void v2MerchantRegisterAndKey() throws Exception {
        // 注册
        MvcResult result = mockMvc.perform(post("/api/v2/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"V2FlowShop\",\"email\":\"v2flow@test.com\","
                                + "\"settlementAddress\":\"1V2FlowAddr000000000000000000000000000\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        merchantId = Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 验证
        mockMvc.perform(post("/api/v2/merchants/" + merchantId + "/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());

        // 生成 API Key
        MvcResult keyResult = mockMvc.perform(post("/api/v2/merchants/" + merchantId + "/api-keys"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").exists())
                .andReturn();
        apiKey = keyResult.getResponse().getContentAsString()
                .replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
        assertNotNull(apiKey);
    }

    @Test
    @Order(5)
    @DisplayName("v2 创建订单（带 API Key）")
    void v2CreateOrder() throws Exception {
        String body = "{\"merchantId\":\"" + merchantId + "\",\"amount\":100000,"
                + "\"description\":\"v2 test order 1\",\"notifyUrl\":\"http://cb.test\"}";
        MvcResult result = mockMvc.perform(post("/api/v2/orders")
                        .header("X-NexusChain-ApiKey", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        order1Id = Long.parseLong(result.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));

        // 再创建一个订单用于分页测试
        String body2 = "{\"merchantId\":\"" + merchantId + "\",\"amount\":200000,"
                + "\"description\":\"v2 test order 2\",\"notifyUrl\":\"http://cb.test\"}";
        MvcResult r2 = mockMvc.perform(post("/api/v2/orders")
                        .header("X-NexusChain-ApiKey", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isCreated())
                .andReturn();
        order2Id = Long.parseLong(r2.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    @Order(6)
    @DisplayName("v2 查询订单详情（字段筛选 fields=id,amount,status）")
    void v2GetOrderWithFields() throws Exception {
        mockMvc.perform(get("/api/v2/orders/" + order1Id + "?fields=id,amount,status")
                        .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.amount").exists())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.orderNo").doesNotExist());
    }

    @Test
    @Order(7)
    @DisplayName("v2 查询订单详情 - 未知字段 → 400 + INVALID_FIELDS")
    void v2GetOrderWithInvalidFields() throws Exception {
        mockMvc.perform(get("/api/v2/orders/" + order1Id + "?fields=id,nonexistent")
                        .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FIELDS"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.error.traceId").exists());
    }

    @Test
    @Order(8)
    @DisplayName("v2 订单不存在 → 404 + ORDER_NOT_FOUND + 统一错误格式")
    void v2OrderNotFound() throws Exception {
        mockMvc.perform(get("/api/v2/orders/999999")
                        .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").exists())
                .andExpect(jsonPath("$.error.details.orderId").value(999999))
                .andExpect(jsonPath("$.error.traceId").exists());
    }

    @Test
    @Order(9)
    @DisplayName("v2 订单列表（游标分页首页）")
    void v2ListOrdersFirstPage() throws Exception {
        mockMvc.perform(get("/api/v2/orders?pageSize=10")
                        .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.hasMore").exists())
                .andExpect(jsonPath("$.count").exists())
                .andExpect(jsonPath("$.pageSize").value(10));
    }

    @Test
    @Order(10)
    @DisplayName("v2 订单列表（字段筛选 + 商户过滤）")
    void v2ListOrdersWithFieldsAndMerchant() throws Exception {
        mockMvc.perform(get("/api/v2/orders?fields=id,status&merchantId=" + merchantId + "&pageSize=10")
                        .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].status").exists())
                .andExpect(jsonPath("$.data[0].amount").doesNotExist());
    }

    @Test
    @Order(11)
    @DisplayName("v2 订单列表 - 无效游标 → 400 + INVALID_CURSOR")
    void v2ListOrdersInvalidCursor() throws Exception {
        mockMvc.perform(get("/api/v2/orders?cursor=!!!invalid!!!")
                        .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
    }

    @Test
    @Order(12)
    @DisplayName("v2 批量创建支付（ALL_OR_NOTHING 全部成功）")
    void v2BatchCreatePaymentsAllSuccess() throws Exception {
        String body = "{\"payments\":["
                + "{\"merchantId\":" + merchantId + ",\"amount\":10000,\"notifyUrl\":\"http://cb.test\"},"
                + "{\"merchantId\":" + merchantId + ",\"amount\":20000,\"notifyUrl\":\"http://cb.test\"}"
                + "],\"onFailure\":\"ALL_OR_NOTHING\"}";
        mockMvc.perform(post("/api/v2/payments/batch")
                        .header("X-NexusChain-ApiKey", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.succeeded").isArray())
                .andExpect(jsonPath("$.succeeded.length()").value(2))
                .andExpect(jsonPath("$.failed.length()").value(0))
                .andExpect(jsonPath("$.totalCount").value(2))
                .andExpect(jsonPath("$.succeededCount").value(2));
    }

    @Test
    @Order(13)
    @DisplayName("v2 批量创建支付 - 空列表 → 400")
    void v2BatchCreatePaymentsEmptyList() throws Exception {
        mockMvc.perform(post("/api/v2/payments/batch")
                        .header("X-NexusChain-ApiKey", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payments\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").exists());
    }

    @Test
    @Order(14)
    @DisplayName("v2 Header 版本协商 - X-NexusChain-API-Version=2")
    void v2HeaderVersionNegotiation() throws Exception {
        // 即使路径是 /api/v2/*，显式发送 Header 也应一致
        mockMvc.perform(get("/api/v2/merchants/" + merchantId)
                        .header("X-NexusChain-ApiKey", apiKey)
                        .header("X-NexusChain-API-Version", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-NexusChain-API-Version", "2"));
    }

    @Test
    @Order(15)
    @DisplayName("v2 无 API Key → 401（除公开端点外）")
    void v2NoApiKey_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v2/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(16)
    @DisplayName("v2 商户端点公开（无需 API Key）")
    void v2MerchantEndpointPublic() throws Exception {
        mockMvc.perform(post("/api/v2/merchants/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantName\":\"V2PublicShop\",\"email\":\"v2pub@test.com\","
                                + "\"settlementAddress\":\"1V2PubAddr000000000000000000000000000\"}"))
                .andExpect(status().isCreated());
    }
}