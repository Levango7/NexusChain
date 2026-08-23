package org.nexus.gateway.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.PaymentService;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PaymentController} 商户归属校验（P0-4 IDOR 防护）Web 层测试。
 *
 * <p>使用 MockMvc standalone + 真实 {@link MerchantOwnershipGuard} 与
 * {@link GlobalExceptionHandler}，通过 requestAttr 模拟 ApiKeyInterceptor
 * 注入的认证商户上下文。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentControllerOwnershipWebTest {

    private static final String MERCHANT_ATTR = MerchantOwnershipGuard.MERCHANT_ID_ATTR;

    @Mock private OrderService orderService;
    @Mock private PaymentService paymentService;

    private MockMvc mockMvc;
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        PaymentController controller =
                new PaymentController(orderService, paymentService, new MerchantOwnershipGuard());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        order = new PaymentOrder();
        order.setId(1L);
        order.setOrderNo("NEX-WEB-001");
        order.setMerchantId(100L);
        order.setAmount(new BigDecimal("1000000"));
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
    }

    @Test
    @DisplayName("跨商户读取订单：403")
    void getOrder_crossMerchantForbidden() throws Exception {
        when(orderService.findById(1L)).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/v1/orders/1").requestAttr(MERCHANT_ATTR, 200L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40320));
    }

    @Test
    @DisplayName("属主商户读取订单：200")
    void getOrder_ownerAllowed() throws Exception {
        when(orderService.findById(1L)).thenReturn(Optional.of(order));

        mockMvc.perform(get("/api/v1/orders/1").requestAttr(MERCHANT_ATTR, 100L))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("缺少认证商户上下文：fail-closed 返回 403")
    void getOrder_missingMerchantContextForbidden() throws Exception {
        // Guard 在加载订单前即拒绝，无需订单桩
        mockMvc.perform(get("/api/v1/orders/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("跨商户发起退款：403")
    void refund_crossMerchantForbidden() throws Exception {
        when(orderService.findById(1L)).thenReturn(Optional.of(order));

        mockMvc.perform(post("/api/v1/orders/1/refund")
                        .requestAttr(MERCHANT_ATTR, 200L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("createOrder：请求体 merchantId 被认证上下文覆盖（防伪造归属）")
    void createOrder_merchantIdOverriddenFromContext() throws Exception {
        PaymentOrder created = new PaymentOrder();
        created.setId(9L);
        created.setOrderNo("NEX-NEW");
        created.setMerchantId(100L);
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(created);

        mockMvc.perform(post("/api/v1/orders")
                        .requestAttr(MERCHANT_ATTR, 100L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"999\",\"amount\":1000000,"
                                + "\"notifyUrl\":\"https://m.example.com/hook\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        org.mockito.Mockito.verify(orderService).createOrder(captor.capture());
        assertEquals("100", captor.getValue().getMerchantId(),
                "请求体中的 merchantId 必须被覆盖为认证商户");
    }
}
