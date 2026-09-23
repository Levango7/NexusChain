package org.nexus.gateway.split;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SplitController 单元测试 — 使用 MockMvc + Mockito（standaloneSetup，无 Spring 上下文）。
 *
 * <p>注入 GlobalExceptionHandler 使异常处理与 production 行为一致。
 * 所有依赖均通过 Mockito mock。</p>
 */
class SplitControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private SplitService splitService;
    private MerchantOwnershipGuard ownershipGuard;

    private static final Long MERCHANT_ID = 500L;
    private static final String RECEIVER_ADDR = "0xReceiver1234567890abcdef1234567890abcdef123456";

    @BeforeEach
    void setUp() {
        splitService = mock(SplitService.class);
        ownershipGuard = mock(MerchantOwnershipGuard.class);

        SplitController controller = new SplitController(splitService, ownershipGuard);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    // ==================== POST /api/v1/split/rules ====================

    @Test
    @DisplayName("POST /rules 创建 RATIO 规则 — 200")
    void createRatioRuleSuccess() throws Exception {
        SplitRule rule = new SplitRule();
        rule.setId(1L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setSplitType(SplitRule.SplitType.RATIO);
        rule.setSplitValue(new BigDecimal("500"));
        rule.setReceiverAddress(RECEIVER_ADDR);
        rule.setDescription("分销商佣金");
        rule.setActive(true);
        rule.setPriority(10);

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(splitService.createSplitRule(eq(MERCHANT_ID), eq(SplitRule.SplitType.RATIO),
                any(BigDecimal.class), eq(RECEIVER_ADDR), eq("分销商佣金")))
                .thenReturn(rule);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("splitType", "RATIO");
        requestBody.put("splitValue", 500);
        requestBody.put("receiverAddress", RECEIVER_ADDR);
        requestBody.put("description", "分销商佣金");

        mockMvc.perform(post("/api/v1/split/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.splitType").value("RATIO"))
                .andExpect(jsonPath("$.splitValue").value(500))
                .andExpect(jsonPath("$.receiverAddress").value(RECEIVER_ADDR))
                .andExpect(jsonPath("$.description").value("分销商佣金"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("POST /rules 创建 FIXED 规则 — 200")
    void createFixedRuleSuccess() throws Exception {
        SplitRule rule = new SplitRule();
        rule.setId(2L);
        rule.setMerchantId(MERCHANT_ID);
        rule.setSplitType(SplitRule.SplitType.FIXED);
        rule.setSplitValue(new BigDecimal("100.50"));
        rule.setReceiverAddress(RECEIVER_ADDR);
        rule.setDescription("平台费");
        rule.setActive(true);
        rule.setPriority(0);

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(splitService.createSplitRule(eq(MERCHANT_ID), eq(SplitRule.SplitType.FIXED),
                any(BigDecimal.class), eq(RECEIVER_ADDR), eq("平台费")))
                .thenReturn(rule);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("splitType", "FIXED");
        requestBody.put("splitValue", 100.50);
        requestBody.put("receiverAddress", RECEIVER_ADDR);
        requestBody.put("description", "平台费");

        mockMvc.perform(post("/api/v1/split/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.splitType").value("FIXED"))
                .andExpect(jsonPath("$.description").value("平台费"));
    }

    @Test
    @DisplayName("POST /rules 创建无效规则（value > 10000）— 400")
    void createRuleInvalidValueReturns400() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(splitService.createSplitRule(eq(MERCHANT_ID), eq(SplitRule.SplitType.RATIO),
                any(BigDecimal.class), anyString(), any()))
                .thenThrow(new IllegalArgumentException("RATIO splitValue must be <= 10000 (basis points)"));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("splitType", "RATIO");
        requestBody.put("splitValue", 10001);
        requestBody.put("receiverAddress", RECEIVER_ADDR);

        mockMvc.perform(post("/api/v1/split/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest());
    }

    // ==================== GET /api/v1/split/rules ====================

    @Test
    @DisplayName("GET /rules — 200 + 返回列表")
    void getSplitRulesReturnsList() throws Exception {
        SplitRule rule1 = new SplitRule();
        rule1.setId(1L);
        rule1.setMerchantId(MERCHANT_ID);
        rule1.setSplitType(SplitRule.SplitType.RATIO);
        rule1.setSplitValue(new BigDecimal("500"));
        rule1.setReceiverAddress(RECEIVER_ADDR);
        rule1.setActive(true);

        SplitRule rule2 = new SplitRule();
        rule2.setId(2L);
        rule2.setMerchantId(MERCHANT_ID);
        rule2.setSplitType(SplitRule.SplitType.FIXED);
        rule2.setSplitValue(new BigDecimal("100"));
        rule2.setReceiverAddress("0xAnother");
        rule2.setActive(true);

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(splitService.getSplitRules(MERCHANT_ID)).thenReturn(List.of(rule1, rule2));

        mockMvc.perform(get("/api/v1/split/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].splitType").value("RATIO"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].splitType").value("FIXED"));
    }

    // ==================== DELETE /api/v1/split/rules/{ruleId} ====================

    @Test
    @DisplayName("DELETE /rules/{id} — 204")
    void deactivateSplitRuleReturns204() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        doNothing().when(splitService).deactivateSplitRule(1L, MERCHANT_ID);

        mockMvc.perform(delete("/api/v1/split/rules/1"))
                .andExpect(status().isNoContent());

        verify(splitService).deactivateSplitRule(1L, MERCHANT_ID);
    }

    @Test
    @DisplayName("DELETE /rules/{id} 规则不属于商户 — 403")
    void deactivateSplitRuleNotOwnedReturns403() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        doThrow(new MerchantOwnershipException("Access denied"))
                .when(splitService).deactivateSplitRule(1L, MERCHANT_ID);

        mockMvc.perform(delete("/api/v1/split/rules/1"))
                .andExpect(status().isForbidden());
    }

    // ==================== GET /api/v1/split/orders/{orderNo} ====================

    @Test
    @DisplayName("GET /orders/{orderNo} — 200 + 返回分账明细")
    void getSplitOrdersReturnsDetails() throws Exception {
        SplitOrder order1 = new SplitOrder();
        order1.setId(1L);
        order1.setOrderId("ORD-001");
        order1.setMerchantId(MERCHANT_ID);
        order1.setReceiverAddress(RECEIVER_ADDR);
        order1.setAmount(new BigDecimal("50"));
        order1.setSplitType(SplitRule.SplitType.RATIO);
        order1.setSplitValue(new BigDecimal("500"));
        order1.setStatus(SplitOrder.SplitStatus.PENDING);

        SplitOrder order2 = new SplitOrder();
        order2.setId(2L);
        order2.setOrderId("ORD-001");
        order2.setMerchantId(MERCHANT_ID);
        order2.setReceiverAddress("0xAnother");
        order2.setAmount(new BigDecimal("100"));
        order2.setSplitType(SplitRule.SplitType.FIXED);
        order2.setSplitValue(new BigDecimal("100"));
        order2.setStatus(SplitOrder.SplitStatus.PENDING);

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(splitService.getSplitOrders("ORD-001")).thenReturn(List.of(order1, order2));

        mockMvc.perform(get("/api/v1/split/orders/ORD-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].orderId").value("ORD-001"))
                .andExpect(jsonPath("$[0].amount").value(50))
                .andExpect(jsonPath("$[0].splitType").value("RATIO"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].splitType").value("FIXED"));
    }

    @Test
    @DisplayName("GET /orders/{orderNo} 无分账 — 200 + 空列表")
    void getSplitOrdersEmptyReturns200() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(splitService.getSplitOrders("ORD-999")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/split/orders/ORD-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}