package org.nexus.gateway.orchestration;

import org.junit.jupiter.api.*;
import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E2E integration tests for the Payment Orchestration Engine.
 * Tests the full lifecycle: create → route → connector → confirm → query.
 *
 * <p>The orchestration API ({@code /api/v1/payments/**}) is protected by both
 * {@link ApiKeyInterceptor} (merchant API-key auth) and
 * {@link RequestSignatureInterceptor} (HMAC request signing). These E2E tests
 * exercise the orchestration engine itself, not the auth perimeter, so both
 * interceptors are replaced with mocks that always pass through.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrchestrationE2ETest {

    @Autowired
    private MockMvc mockMvc;

    // Replace the auth interceptors with no-op mocks so the E2E tests can drive
    // the orchestration engine directly. WebConfig registers these interceptor
    // beans into the Spring MVC interceptor chain; by substituting Mockito mocks
    // whose preHandle() returns true, every /api/v1/payments/** request passes
    // through unauthenticated. This scope is limited to this test class only —
    // other integration tests keep the real interceptors.
    @MockBean
    private ApiKeyInterceptor apiKeyInterceptor;

    @MockBean
    private RequestSignatureInterceptor requestSignatureInterceptor;

    private static String paymentId;

    @BeforeEach
    void stubAuthInterceptors() throws Exception {
        when(apiKeyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(requestSignatureInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    // === Connector Discovery ===

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("List registered connectors")
    void listConnectors() throws Exception {
        mockMvc.perform(get("/api/v1/payments/connectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Check mock connector health")
    void checkMockHealth() throws Exception {
        mockMvc.perform(get("/api/v1/payments/connectors/mock/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.connectorId").value("mock"));
    }

    // === Payment Lifecycle (Mock Connector) ===

    @Test
    @org.junit.jupiter.api.Order(10)
    @DisplayName("Create payment via mock connector (explicit routing)")
    void createPaymentMock() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "amount": 50000,
                        "currency": "NEX",
                        "description": "E2E Test: Mock Payment",
                        "merchant_id": "1",
                        "routing": { "preferred_connector": "mock" }
                    }
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.connector").value("mock"))
                .andExpect(jsonPath("$.amount").value(50000))
                .andExpect(jsonPath("$.currency").value("NEX"))
                .andReturn();

        String body = res.getResponse().getContentAsString();
        paymentId = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertNotNull(paymentId);
        assertTrue(paymentId.startsWith("pay_"));
    }

    @Test
    @org.junit.jupiter.api.Order(11)
    @DisplayName("Query payment by ID")
    void queryPayment() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.confirmed_at").exists());
    }

    @Test
    @org.junit.jupiter.api.Order(12)
    @DisplayName("List payments with pagination")
    void listPayments() throws Exception {
        mockMvc.perform(get("/api/v1/payments")
                .param("merchantId", "1")
                .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // === Routing Rules ===

    @Test
    @org.junit.jupiter.api.Order(20)
    @DisplayName("List default routing rules")
    void listRoutingRules() throws Exception {
        mockMvc.perform(get("/api/v1/payments/routing-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    @org.junit.jupiter.api.Order(21)
    @DisplayName("Add custom routing rule")
    void addRoutingRule() throws Exception {
        mockMvc.perform(post("/api/v1/payments/routing-rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "id": "test-rule-usd",
                        "name": "USD goes to mock",
                        "conditions": { "currency": "USD" },
                        "strategy": "PRIORITY",
                        "connectors": ["mock"],
                        "priority": 20
                    }
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("test-rule-usd"));
    }

    @Test
    @org.junit.jupiter.api.Order(22)
    @DisplayName("Delete routing rule")
    void deleteRoutingRule() throws Exception {
        mockMvc.perform(delete("/api/v1/payments/routing-rules/test-rule-usd"))
                .andExpect(status().isNoContent());
    }

    // === Auto-Routing (no explicit connector) ===

    @Test
    @org.junit.jupiter.api.Order(30)
    @DisplayName("Create payment with auto-routing (NEX → chain priority)")
    void createPaymentAutoRoute() throws Exception {
        // NEX currency should route to chain first (priority rule), fall back to mock
        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "amount": 10000,
                        "currency": "NEX",
                        "description": "E2E Test: Auto-routed NEX payment",
                        "merchant_id": "1"
                    }
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.routing_strategy").value("priority"))
                .andExpect(jsonPath("$.connector").exists());
    }

    @Test
    @org.junit.jupiter.api.Order(31)
    @DisplayName("Create payment with non-NEX currency (fallback to mock)")
    void createPaymentNonNex() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "amount": 9900,
                        "currency": "USD",
                        "description": "E2E Test: USD payment fallback",
                        "merchant_id": "1"
                    }
                    """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.connector").value("mock"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    // === Error Handling ===

    @Test
    @org.junit.jupiter.api.Order(40)
    @DisplayName("Reject payment with missing amount")
    void rejectMissingAmount() throws Exception {
        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    { "currency": "NEX", "description": "no amount" }
                    """))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @org.junit.jupiter.api.Order(41)
    @DisplayName("Return 404 for non-existent payment")
    void notFoundPayment() throws Exception {
        mockMvc.perform(get("/api/v1/payments/pay_nonexistent123"))
                .andExpect(status().isNotFound());
    }

    // === Refresh Status ===

    @Test
    @org.junit.jupiter.api.Order(50)
    @DisplayName("Refresh payment status (idempotent for SUCCEEDED)")
    void refreshPayment() throws Exception {
        mockMvc.perform(post("/api/v1/payments/" + paymentId + "/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }
}