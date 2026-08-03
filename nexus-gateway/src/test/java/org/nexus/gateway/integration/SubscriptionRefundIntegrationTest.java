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
 * Subscription + Refund flow integration test.
 * Tests: create subscription → charge → cancel → refund via order API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SubscriptionRefundIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String apiKey;
    private static Long merchantId;
    private static Long subscriptionId;
    private static Long orderId;

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Setup merchant for subscription tests")
    void setupMerchant() throws Exception {
        MvcResult reg = mockMvc.perform(post("/api/v1/merchants/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"Sub Service Inc\",\"email\":\"sub@test.io\",\"settlementAddress\":\"1SubAddr00000000000000000000000000000\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        merchantId = Long.parseLong(reg.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());

        MvcResult keyRes = mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        apiKey = keyRes.getResponse().getContentAsString().replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Create subscription")
    void createSubscription() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/subscriptions")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":" + merchantId + ",\"payerAddress\":\"1PayerAddr000000000000000000000000000\",\"payeeAddress\":\"1PayeeAddr000000000000000000000000000\",\"amount\":99.00,\"cycleDays\":30}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        subscriptionId = Long.parseLong(res.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Charge subscription")
    void chargeSubscription() throws Exception {
        mockMvc.perform(post("/api/v1/subscriptions/" + subscriptionId + "/charge")
                .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainTxHash").exists());
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Cancel subscription")
    void cancelSubscription() throws Exception {
        mockMvc.perform(post("/api/v1/subscriptions/" + subscriptionId + "/cancel")
                .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Reject charge after cancellation")
    void rejectChargeAfterCancel() throws Exception {
        mockMvc.perform(post("/api/v1/subscriptions/" + subscriptionId + "/charge")
                .header("X-NexusChain-ApiKey", apiKey))
                .andExpect(status().isConflict());
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Create order and refund it")
    void createOrderAndRefund() throws Exception {
        // Create order
        MvcResult orderRes = mockMvc.perform(post("/api/v1/orders")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":\"" + merchantId + "\",\"amount\":50000,\"description\":\"Refund test order\",\"notifyUrl\":\"http://localhost:9999/cb\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        orderId = Long.parseLong(orderRes.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Pay the order
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/pay")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAddress\":\"1PayerAddr000000000000000000000000000\"}"))
                .andExpect(status().isOk());

        // Confirm payment
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chainTxHash\":\"abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));

        // Refund
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                .header("X-NexusChain-ApiKey", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":50000,\"reason\":\"Customer request\"}"))
                .andExpect(status().isCreated());
    }
}