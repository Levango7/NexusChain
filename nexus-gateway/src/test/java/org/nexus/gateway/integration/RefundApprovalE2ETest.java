package org.nexus.gateway.integration;

import org.junit.jupiter.api.*;
import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 退款多级审批 E2E 测试。模拟退款审批链全流程。
 *
 * <p>退款 API（{@code /api/v1/refunds/**}）受 {@link ApiKeyInterceptor} 保护，
 * 本测试聚焦审批链路本身而非鉴权边界，因此将 ApiKeyInterceptor 替换为 no-op mock，
 * 使所有请求直接放行。此模式与 {@code OrchestrationE2ETest} 对齐。</p>
 *
 * <p><b>暂时禁用原因：</b>当前系统尚未实现独立的退款审批 API
 * （{@code POST /api/v1/refunds}、{@code POST /api/v1/refunds/approve}）。
 * 现有退款端点为 {@code POST /api/v1/orders/{id}/refund}（见 {@link
 * org.nexus.gateway.controller.PaymentController#refund}），不支持多级审批链。
 * 待退款审批链路 API 实现后重新启用。</p>
 */
@Disabled("退款审批链路 API（/api/v1/refunds, /api/v1/refunds/approve）尚未实现")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefundApprovalE2ETest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ChainConnector chainConnector;

    // 替换鉴权拦截器为 no-op mock，让退款审批 E2E 测试直接驱动业务链路。
    // WebConfig 将该 bean 注册到 Spring MVC 拦截器链；用 Mockito mock 替换后，
    // preHandle() 默认返回 false 会拒绝所有请求，因此必须在 setup 中 stub 为 true。
    // 作用域仅限本测试类，其他集成测试仍使用真实拦截器。
    @MockBean private ApiKeyInterceptor apiKeyInterceptor;

    @BeforeEach
    void setup() throws Exception {
        reset(chainConnector);
        // 放行所有请求，绕过 API Key 鉴权
        when(apiKeyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
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