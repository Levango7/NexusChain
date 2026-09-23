package org.nexus.gateway.resilience;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ResilienceController} 单元测试 — 使用 MockMvc + Mockito（standaloneSetup，无 Spring 上下文）。
 */
class ResilienceControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private ConnectorCircuitBreaker circuitBreaker;
    private ConnectorCircuitBreakerConfig config;
    private FallbackRouter fallbackRouter;
    private ResilienceMetrics metrics;

    @BeforeEach
    void setUp() {
        circuitBreaker = mock(ConnectorCircuitBreaker.class);
        config = mock(ConnectorCircuitBreakerConfig.class);
        fallbackRouter = mock(FallbackRouter.class);
        metrics = mock(ResilienceMetrics.class);

        // 默认配置返回值
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties defaultProps =
                new ConnectorCircuitBreakerConfig.CircuitBreakerProperties();
        when(config.getEffectiveConfig(any(String.class))).thenReturn(defaultProps);

        ResilienceController controller = new ResilienceController(
                circuitBreaker, config, fallbackRouter, metrics);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("GET /circuit-breakers — 返回所有熔断器状态")
    void getAllCircuitBreakers_returnsStates() throws Exception {
        when(circuitBreaker.getRegisteredConnectors()).thenReturn(Set.of("chain", "stripe"));
        when(circuitBreaker.getConnectorState("chain")).thenReturn(CircuitBreaker.State.CLOSED);
        when(circuitBreaker.getConnectorState("stripe")).thenReturn(CircuitBreaker.State.OPEN);
        when(circuitBreaker.getCircuitBreaker("chain")).thenReturn(null);
        when(circuitBreaker.getCircuitBreaker("stripe")).thenReturn(null);

        mockMvc.perform(get("/api/v1/resilience/circuit-breakers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        verify(metrics).refreshAllGauges();
    }

    @Test
    @DisplayName("POST /circuit-breakers/{connector}/reset — 重置熔断器")
    void resetCircuitBreaker_resetsAndReturnsState() throws Exception {
        when(circuitBreaker.getConnectorState("chain")).thenReturn(CircuitBreaker.State.CLOSED);
        when(circuitBreaker.getCircuitBreaker("chain")).thenReturn(null);

        mockMvc.perform(post("/api/v1/resilience/circuit-breakers/chain/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connector").value("chain"))
                .andExpect(jsonPath("$.state").value("CLOSED"));

        verify(circuitBreaker).reset("chain");
        verify(metrics).refreshAllGauges();
    }

    @Test
    @DisplayName("GET /fallbacks — 返回所有降级路由配置")
    void getAllFallbacks_returnsRoutes() throws Exception {
        when(fallbackRouter.getAllRoutes()).thenReturn(Map.of(
                "chain", List.of("consortium", "mock"),
                "stripe", List.of("adyen", "mock")
        ));

        mockMvc.perform(get("/api/v1/resilience/fallbacks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @DisplayName("PUT /fallbacks/{connector} — 更新降级路由配置")
    void updateFallback_updatesAndReturns() throws Exception {
        FallbackConfigDTO dto = new FallbackConfigDTO("chain", List.of("consortium", "mock"));
        when(fallbackRouter.getFallbacks("chain")).thenReturn(List.of("consortium", "mock"));

        mockMvc.perform(put("/api/v1/resilience/fallbacks/chain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connector").value("chain"))
                .andExpect(jsonPath("$.fallbacks[0]").value("consortium"));

        verify(fallbackRouter).updateRoute("chain", List.of("consortium", "mock"));
    }

    @Test
    @DisplayName("GET /metrics — 返回熔断指标汇总")
    void getMetrics_returnsSummary() throws Exception {
        when(circuitBreaker.getRegisteredConnectors()).thenReturn(Set.of("chain"));
        when(circuitBreaker.getConnectorState("chain")).thenReturn(CircuitBreaker.State.CLOSED);
        when(circuitBreaker.getCircuitBreaker("chain")).thenReturn(null);
        when(fallbackRouter.getAllRoutes()).thenReturn(Map.of(
                "chain", List.of("consortium", "mock")
        ));

        mockMvc.perform(get("/api/v1/resilience/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers").exists())
                .andExpect(jsonPath("$.fallbackRoutes").exists());

        verify(metrics).refreshAllGauges();
    }
}