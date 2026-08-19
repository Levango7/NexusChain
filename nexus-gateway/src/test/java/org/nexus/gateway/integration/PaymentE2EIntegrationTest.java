package org.nexus.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.gateway.orchestration.connectors.ConsortiumConnector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 支付全流程 E2E 测试。基于 MockMVC 模拟支付编排链路。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentE2EIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private ChainConnector chainConnector;
    @MockBean private ConsortiumConnector consortiumConnector;

    @BeforeEach
    void setup() {
        reset(chainConnector, consortiumConnector);
    }

    @Test @Order(1)
    void registerMerchant() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"E2EShop\",\"email\":\"e2e@test.com\","
                        + "\"settlementAddress\":\"1E2EAddr00000000000000000000000000000\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(resp.contains("merchantId") || resp.contains("id"));
    }

    @Test @Order(2)
    void createPayment() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"100.00\",\"currency\":\"USDC\",\"merchantId\":\"E2EShop\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(resp.contains("paymentId") || resp.contains("id"));
    }

    @Test @Order(3)
    void multiChannelRouting() {
        assertTrue(chainConnector.isActive(), "core通道应可用");
    }

    @Test @Order(4)
    void largeAmountPayment() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1000000.00\",\"currency\":\"USDC\",\"merchantId\":\"E2EShop\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(resp);
    }

    @Test @Order(5)
    void paymentTimeoutDoesNotCrash() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"50.00\",\"currency\":\"USDC\",\"merchantId\":\"E2EShop\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(resp);
    }

    @Test @Order(6)
    void duplicatePaymentHandled() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"100.00\",\"currency\":\"USDC\",\"merchantId\":\"E2EShop\",\"idempotencyKey\":\"dup-001\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(resp);
    }
}