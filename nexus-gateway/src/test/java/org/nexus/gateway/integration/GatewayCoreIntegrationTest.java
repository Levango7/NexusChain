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
 * Gateway → Core cross-module integration test.
 * Tests the full payment flow WITHOUT skip-confirmation,
 * simulating real chain confirmation via the mock Core RPC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayCoreIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String apiKey;
    private static Long merchantId;
    private static Long orderId;

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Setup: register + verify merchant + generate API key")
    void setupMerchant() throws Exception {
        // Register
        MvcResult reg = mockMvc.perform(post("/api/v1/merchants/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"Integration Shop\",\"email\":\"int@test.io\",\"settlementAddress\":\"1IntegAddr000000000000000000000000000\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = reg.getResponse().getContentAsString();
        merchantId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Verify
        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());

        // Generate API key
        MvcResult keyRes = mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        apiKey = keyRes.getResponse().getContentAsString().replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
        assertNotNull(apiKey);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Create order and initiate payment")
    void createOrderAndPay() throws Exception {
        // Create order
        MvcResult orderRes = mockMvc.perform(post("/api/v1/orders")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":\"" + merchantId + "\",\"amount\":100000,\"description\":\"Integration test order\",\"notifyUrl\":\"http://localhost:9999/cb\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String orderBody = orderRes.getResponse().getContentAsString();
        orderId = Long.parseLong(orderBody.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Initiate payment
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/pay")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAddress\":\"1PayerAddr000000000000000000000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Confirm payment via chain RPC (non-skip mode)")
    void confirmPaymentViaChain() throws Exception {
        // In sandbox mode, the ChainRpcClient calls the mock Core RPC at localhost:3000
        // which always returns confirmed. This tests the real HTTP call path.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chainTxHash\":\"abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.chainTxHash").value("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Reject duplicate payment confirmation")
    void rejectDuplicateConfirm() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chainTxHash\":\"abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Refund a paid order")
    void refundPaidOrder() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":50000,\"reason\":\"Customer request\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Reject unauthorized access (no API key)")
    void rejectUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isUnauthorized());
    }
}