package org.nexus.gateway.integration;

import org.junit.jupiter.api.*;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 退款多级审批 E2E 测试。模拟退款审批链全流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefundApprovalE2ETest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ChainConnector chainConnector;

    @BeforeEach
    void setup() {
        reset(chainConnector);

    }

    @Test @Order(1)
    void requestRefund() throws Exception {
        mockMvc.perform(post("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"pay_test_001\",\"amount\":\"50.00\",\"reason\":\"customer request\"}"))
                .andExpect(status().isOk());
    }

    @Test @Order(2)
    void multiLevelApprovalChain() throws Exception {
        // L1: 初级审批
        mockMvc.perform(post("/api/v1/refunds/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundId\":\"ref_test_001\",\"approver\":\"l1_approver\",\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test @Order(3)
    void finalApprovalLargeAmount() throws Exception {
        mockMvc.perform(post("/api/v1/refunds/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundId\":\"ref_test_001\",\"approver\":\"l2_approver\",\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test @Order(4)
    void refundRejectedByApprover() throws Exception {
        mockMvc.perform(post("/api/v1/refunds/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundId\":\"ref_test_002\",\"approver\":\"l1_approver\",\"approved\":false,\"reason\":\"violates policy\"}"))
                .andExpect(status().isOk());
    }

    @Test @Order(5)
    void autoRefundOnTimeout() throws Exception {
        mockMvc.perform(post("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"pay_timeout_001\",\"amount\":\"10.00\",\"autoRefund\":true}"))
                .andExpect(status().isOk());
    }

    @Test @Order(6)
    void partialRefund() throws Exception {
        mockMvc.perform(post("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentId\":\"pay_test_003\",\"amount\":\"25.00\",\"originalAmount\":\"100.00\"}"))
                .andExpect(status().isOk());
    }
}