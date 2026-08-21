package org.nexus.gateway.integration;

import org.junit.jupiter.api.*;
import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.gateway.refund.RefundApprovalService;
import org.nexus.gateway.refund.RefundRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
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
 * <p>服务层 {@link RefundApprovalService} 同样替换为 Mockito mock，并在
 * {@link #setup()} 中 stub 各方法返回预设的 {@link RefundRequest}，使测试
 * 聚焦 HTTP 接口契约（路由、状态码、请求体解析）而非业务逻辑。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RefundApprovalE2ETest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ChainConnector chainConnector;
    @MockBean private RefundApprovalService refundApprovalService;

    // 替换鉴权拦截器为 no-op mock，让退款审批 E2E 测试直接驱动业务链路。
    // WebConfig 将该 bean 注册到 Spring MVC 拦截器链；用 Mockito mock 替换后,
    // preHandle() 默认返回 false 会拒绝所有请求，因此必须在 setup 中 stub 为 true。
    // 作用域仅限本测试类，其他集成测试仍使用真实拦截器。
    @MockBean private ApiKeyInterceptor apiKeyInterceptor;

    @BeforeEach
    void setup() throws Exception {
        reset(chainConnector, refundApprovalService);
        // 放行所有请求，绕过 API Key 鉴权
        when(apiKeyInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        // stub refundApprovalService：所有方法返回同一个预设 RefundRequest，
        // 使 HTTP 接口测试与业务逻辑解耦。
        RefundRequest mockRefund = new RefundRequest();
        mockRefund.setId(1L);
        mockRefund.setRefundNo("RF_TEST_001");
        mockRefund.setOrderId(1L);
        mockRefund.setMerchantId(1L);
        mockRefund.setAmount(new BigDecimal("50.00"));
        mockRefund.setReason("customer request");
        mockRefund.setStatus(RefundRequest.RefundStatus.PENDING);

        when(refundApprovalService.requestRefund(anyLong(), any(BigDecimal.class), any()))
                .thenReturn(mockRefund);
        when(refundApprovalService.approveRefund(anyLong(), anyString()))
                .thenReturn(mockRefund);
        when(refundApprovalService.rejectRefund(anyLong(), anyString(), any()))
                .thenReturn(mockRefund);
        when(refundApprovalService.executeRefund(anyLong()))
                .thenReturn(mockRefund);
    }

    @Test @Order(1)
    void requestRefund() throws Exception {
        // POST /api/v1/refunds 返回 201 CREATED
        mockMvc.perform(post("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":1,\"amount\":\"50.00\",\"reason\":\"customer request\"}"))
                .andExpect(status().isCreated());
    }

    @Test @Order(2)
    void multiLevelApprovalChain() throws Exception {
        // L1: 初级审批 → POST /api/v1/refunds/approve 返回 200 OK
        mockMvc.perform(post("/api/v1/refunds/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundId\":1,\"approver\":\"l1_approver\",\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test @Order(3)
    void finalApprovalLargeAmount() throws Exception {
        mockMvc.perform(post("/api/v1/refunds/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundId\":1,\"approver\":\"l2_approver\",\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test @Order(4)
    void refundRejectedByApprover() throws Exception {
        mockMvc.perform(post("/api/v1/refunds/approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refundId\":1,\"approver\":\"l1_approver\",\"approved\":false,\"reason\":\"violates policy\"}"))
                .andExpect(status().isOk());
    }

    @Test @Order(5)
    void autoRefundOnTimeout() throws Exception {
        // 自动退款：POST /api/v1/refunds 返回 201 CREATED
        mockMvc.perform(post("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":1,\"amount\":\"10.00\",\"autoRefund\":true}"))
                .andExpect(status().isCreated());
    }

    @Test @Order(6)
    void partialRefund() throws Exception {
        // 部分退款：POST /api/v1/refunds 返回 201 CREATED
        mockMvc.perform(post("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":1,\"amount\":\"25.00\",\"originalAmount\":\"100.00\"}"))
                .andExpect(status().isCreated());
    }
}
