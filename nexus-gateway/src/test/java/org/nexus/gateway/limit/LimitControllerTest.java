package org.nexus.gateway.limit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * LimitController 单元测试 — 使用 MockMvc + Mockito（standaloneSetup，无 Spring 上下文）。
 *
 * <p>注入 GlobalExceptionHandler 使异常处理与 production 行为一致。
 * 所有依赖均通过 Mockito mock。</p>
 */
class LimitControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private LimitCheckService limitCheckService;
    private MerchantOwnershipGuard ownershipGuard;

    private static final Long MERCHANT_ID = 500L;

    @BeforeEach
    void setUp() {
        limitCheckService = mock(LimitCheckService.class);
        ownershipGuard = mock(MerchantOwnershipGuard.class);

        LimitController controller = new LimitController(limitCheckService, ownershipGuard);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    // ==================== GET /api/v1/limits/config ====================

    @Test
    @DisplayName("GET /config — 有配置 -> 200 + 返回配置")
    void getConfigWithExistingConfig() throws Exception {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setId(1L);
        config.setMerchantId(MERCHANT_ID);
        config.setSingleTransactionMinAmount(new BigDecimal("1"));
        config.setSingleTransactionMaxAmount(new BigDecimal("100000"));
        config.setDailyAccumulatedMaxAmount(new BigDecimal("500000"));
        config.setMonthlyAccumulatedMaxAmount(new BigDecimal("5000000"));
        config.setDailyMaxTransactionCount(100);
        config.setMonthlyMaxTransactionCount(2000);
        config.setActive(true);

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.getConfig(MERCHANT_ID)).thenReturn(config);

        mockMvc.perform(get("/api/v1/limits/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.merchantId").value(MERCHANT_ID.intValue()))
                .andExpect(jsonPath("$.singleTransactionMinAmount").value(1))
                .andExpect(jsonPath("$.singleTransactionMaxAmount").value(100000))
                .andExpect(jsonPath("$.dailyAccumulatedMaxAmount").value(500000))
                .andExpect(jsonPath("$.monthlyAccumulatedMaxAmount").value(5000000))
                .andExpect(jsonPath("$.dailyMaxTransactionCount").value(100))
                .andExpect(jsonPath("$.monthlyMaxTransactionCount").value(2000))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("GET /config — 无配置 -> 200 + 返回默认配置")
    void getConfigWithNoConfigReturnsDefault() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.getConfig(MERCHANT_ID)).thenReturn(null);

        mockMvc.perform(get("/api/v1/limits/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(MERCHANT_ID.intValue()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.singleTransactionMinAmount").doesNotExist())
                .andExpect(jsonPath("$.singleTransactionMaxAmount").doesNotExist());
    }

    // ==================== PUT /api/v1/limits/config ====================

    @Test
    @DisplayName("PUT /config — 更新配置 -> 200")
    void updateConfigSuccess() throws Exception {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setId(1L);
        config.setMerchantId(MERCHANT_ID);
        config.setSingleTransactionMinAmount(new BigDecimal("1"));
        config.setSingleTransactionMaxAmount(new BigDecimal("100000"));
        config.setDailyAccumulatedMaxAmount(new BigDecimal("500000"));
        config.setMonthlyAccumulatedMaxAmount(new BigDecimal("5000000"));
        config.setDailyMaxTransactionCount(100);
        config.setMonthlyMaxTransactionCount(2000);
        config.setActive(true);

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.createOrUpdateConfig(
                eq(MERCHANT_ID), any(), any(), any(), any(), any(), any()))
                .thenReturn(config);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("singleTransactionMinAmount", 1);
        requestBody.put("singleTransactionMaxAmount", 100000);
        requestBody.put("dailyAccumulatedMaxAmount", 500000);
        requestBody.put("monthlyAccumulatedMaxAmount", 5000000);
        requestBody.put("dailyMaxTransactionCount", 100);
        requestBody.put("monthlyMaxTransactionCount", 2000);

        mockMvc.perform(put("/api/v1/limits/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.merchantId").value(MERCHANT_ID.intValue()))
                .andExpect(jsonPath("$.singleTransactionMinAmount").value(1))
                .andExpect(jsonPath("$.singleTransactionMaxAmount").value(100000))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("PUT /config — singleMin > singleMax -> 400")
    void updateConfigSingleMinGreaterThanSingleMaxReturns400() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.createOrUpdateConfig(
                eq(MERCHANT_ID), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException(
                        "singleTransactionMinAmount (1000) must be <= singleTransactionMaxAmount (100)"));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("singleTransactionMinAmount", 1000);
        requestBody.put("singleTransactionMaxAmount", 100);

        mockMvc.perform(put("/api/v1/limits/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest());
    }

    // ==================== GET /api/v1/limits/status ====================

    @Test
    @DisplayName("GET /status — 200 + 返回使用情况")
    void getStatusReturnsUsage() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.getDailyAccumulatedAmount(MERCHANT_ID)).thenReturn(new BigDecimal("12345"));
        when(limitCheckService.getDailyTransactionCount(MERCHANT_ID)).thenReturn(5);
        when(limitCheckService.getMonthlyAccumulatedAmount(MERCHANT_ID)).thenReturn(new BigDecimal("100000"));
        when(limitCheckService.getMonthlyTransactionCount(MERCHANT_ID)).thenReturn(50);

        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setId(1L);
        config.setMerchantId(MERCHANT_ID);
        config.setDailyAccumulatedMaxAmount(new BigDecimal("500000"));
        config.setActive(true);
        when(limitCheckService.getConfig(MERCHANT_ID)).thenReturn(config);

        mockMvc.perform(get("/api/v1/limits/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyAccumulatedAmount").value(12345))
                .andExpect(jsonPath("$.dailyTransactionCount").value(5))
                .andExpect(jsonPath("$.monthlyAccumulatedAmount").value(100000))
                .andExpect(jsonPath("$.monthlyTransactionCount").value(50))
                .andExpect(jsonPath("$.config.id").value(1))
                .andExpect(jsonPath("$.config.dailyAccumulatedMaxAmount").value(500000));
    }

    // ==================== POST /api/v1/limits/check ====================

    @Test
    @DisplayName("POST /check — 通过 -> 200 + passed=true")
    void checkTransactionPassed() throws Exception {
        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.checkLimits(eq(MERCHANT_ID), any(BigDecimal.class)))
                .thenReturn(LimitCheckResult.passed());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("amount", 500);

        mockMvc.perform(post("/api/v1/limits/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));
    }

    @Test
    @DisplayName("POST /check — 超限 -> 200 + passed=false + violationType")
    void checkTransactionExceedsLimit() throws Exception {
        LimitCheckResult failedResult = LimitCheckResult.failed(
                "SINGLE_MAX",
                "Transaction amount 5000 exceeds the maximum allowed 1000",
                null, 0, new BigDecimal("1000"));

        when(ownershipGuard.requireMerchantId(any())).thenReturn(MERCHANT_ID);
        when(limitCheckService.checkLimits(eq(MERCHANT_ID), any(BigDecimal.class)))
                .thenReturn(failedResult);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("amount", 5000);

        mockMvc.perform(post("/api/v1/limits/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.violationType").value("SINGLE_MAX"))
                .andExpect(jsonPath("$.limitValue").value(1000));
    }
}