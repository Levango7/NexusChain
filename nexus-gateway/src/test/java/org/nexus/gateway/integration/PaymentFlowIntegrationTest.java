package org.nexus.gateway.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test: Merchant registration -> Order creation -> Payment -> Confirmation -> Refund.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static String apiKey;
    private static Long merchantId;
    private static Long orderId;
    private static String checkoutToken;

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Step 1: Register merchant")
    void registerMerchant() throws Exception {
        String body = "{\"merchantName\":\"FlowShop\",\"email\":\"flow@test.com\",\"settlementAddress\":\"1FlowAddr00000000000000000000000000000\"}";
        MvcResult result = mockMvc.perform(post("/api/v1/merchants/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantCode").exists())
                .andReturn();
        String json = result.getResponse().getContentAsString();
        merchantId = Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
        assertNotNull(merchantId);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Step 2: Verify merchant + generate API key")
    void verifyAndGenerateKey() throws Exception {
        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.apiKey").exists())
                .andReturn();
        apiKey = result.getResponse().getContentAsString().replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
        assertNotNull(apiKey);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Step 3: Create order with API key")
    void createOrder() throws Exception {
        String body = "{\"merchantId\":\"" + merchantId + "\",\"amount\":500000,\"description\":\"Flow test order\",\"notifyUrl\":\"http://cb.test\"}";
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNo").exists())
                .andExpect(jsonPath("$.checkoutToken").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String json = result.getResponse().getContentAsString();
        orderId = Long.parseLong(json.replaceAll(".*\"id\":(\\d+).*", "$1"));
        checkoutToken = json.replaceAll(".*\"checkoutToken\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Step 4: Checkout info accessible without auth")
    void checkoutInfoPublic() throws Exception {
        mockMvc.perform(get("/api/v1/checkout/info?token=" + checkoutToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").exists())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Step 5: Initiate payment")
    void initiatePayment() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/pay")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAddress\":\"1PayerAddr000000000000000000000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Step 6: Confirm payment (sandbox skip-confirmation)")
    void confirmPayment() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chainTxHash\":\"a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("Step 7: Verify final state is PAID")
    void verifyFinalState() throws Exception {
        mockMvc.perform(get("/api/v1/checkout/status?token=" + checkoutToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.chainTxHash").exists());
    }

    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("Step 8: Reject request without API key (401)")
    void rejectWithoutKey() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isUnauthorized());
    }
}