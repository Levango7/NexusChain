package org.nexus.gateway.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * OpsController 单元测试 — 使用 MockMvc + Mockito（standaloneSetup，无 Spring 上下文）。
 *
 * <p>验证各运维端点的 HTTP 响应状态码、响应体结构和错误处理逻辑。
 * OpsService 通过 Mockito mock 注入。</p>
 */
class OpsControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private OpsService opsService;

    @BeforeEach
    void setUp() {
        opsService = mock(OpsService.class);
        OpsController controller = new OpsController(opsService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    // ==================== GET /api/v1/ops/config ====================

    @Test
    @DisplayName("GET /config — 200 + 返回脱敏配置")
    void getConfigReturnsMaskedConfig() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("nexus.token-symbol", "NEX");
        config.put("nexus.security.jwt.secret", "****");
        when(opsService.getConfig()).thenReturn(config);

        mockMvc.perform(get("/api/v1/ops/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['nexus.token-symbol']").value("NEX"))
                .andExpect(jsonPath("$.['nexus.security.jwt.secret']").value("****"));
    }

    // ==================== POST /api/v1/ops/config/refresh ====================

    @Test
    @DisplayName("POST /config/refresh — 200 + 返回刷新结果")
    void refreshConfigReturnsRefreshResult() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "triggered");
        result.put("message", "配置刷新已触发");
        when(opsService.refreshConfig()).thenReturn(result);

        mockMvc.perform(post("/api/v1/ops/config/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("triggered"))
                .andExpect(jsonPath("$.message").value("配置刷新已触发"));
    }

    // ==================== GET /api/v1/ops/config/diff ====================

    @Test
    @DisplayName("GET /config/diff — 200 + 返回配置差异")
    void getConfigDiffReturnsDiff() throws Exception {
        Map<String, Object> diff = new HashMap<>();
        diff.put("localConfig", Map.of("key", "value"));
        diff.put("remoteConfigAvailable", false);
        when(opsService.getConfigDiff()).thenReturn(diff);

        mockMvc.perform(get("/api/v1/ops/config/diff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remoteConfigAvailable").value(false));
    }

    // ==================== POST /api/v1/ops/cache/clear ====================

    @Test
    @DisplayName("POST /cache/clear — 200 + 清除指定缓存")
    void clearCacheClearsSpecifiedCache() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleared");
        result.put("cacheName", "connectorConfigs");
        when(opsService.clearCache("connectorConfigs")).thenReturn(result);

        Map<String, String> body = new HashMap<>();
        body.put("cacheName", "connectorConfigs");

        mockMvc.perform(post("/api/v1/ops/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cleared"))
                .andExpect(jsonPath("$.cacheName").value("connectorConfigs"));
    }

    @Test
    @DisplayName("POST /cache/clear — cacheName 为空 -> 400")
    void clearCacheWithEmptyCacheNameReturns400() throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("cacheName", "");

        mockMvc.perform(post("/api/v1/ops/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"));
    }

    @Test
    @DisplayName("POST /cache/clear — 缺少 cacheName 字段 -> 400")
    void clearCacheWithoutCacheNameReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/ops/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /cache/clear — 缓存不存在 -> 200 + not_found")
    void clearCacheReturnsNotFoundForMissingCache() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "not_found");
        result.put("message", "缓存 'unknownCache' 不存在");
        when(opsService.clearCache("unknownCache")).thenReturn(result);

        Map<String, String> body = new HashMap<>();
        body.put("cacheName", "unknownCache");

        mockMvc.perform(post("/api/v1/ops/cache/clear")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    // ==================== GET /api/v1/ops/cache/stats ====================

    @Test
    @DisplayName("GET /cache/stats — 200 + 返回缓存统计列表")
    void getCacheStatsReturnsStatsList() throws Exception {
        List<CacheStatsDTO> statsList = List.of(
                new CacheStatsDTO("connectorConfigs", 10, 50, 5, 0.91, 55, 0, 2.5, 3),
                new CacheStatsDTO("merchantLimits", 5, 30, 10, 0.75, 40, 0, 1.8, 1)
        );
        when(opsService.getCacheStats()).thenReturn(statsList);

        mockMvc.perform(get("/api/v1/ops/cache/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cacheName").value("connectorConfigs"))
                .andExpect(jsonPath("$[0].hitRate").value(0.91))
                .andExpect(jsonPath("$[1].cacheName").value("merchantLimits"))
                .andExpect(jsonPath("$[1].hitRate").value(0.75));
    }

    // ==================== POST /api/v1/ops/cache/clear-all ====================

    @Test
    @DisplayName("POST /cache/clear-all — 200 + 清除所有缓存")
    void clearAllCachesClearsAll() throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleared");
        result.put("totalCleared", 3);
        result.put("clearedCaches", List.of("connectorConfigs", "merchantLimits", "settlementConfigs"));
        when(opsService.clearAllCaches()).thenReturn(result);

        mockMvc.perform(post("/api/v1/ops/cache/clear-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cleared"))
                .andExpect(jsonPath("$.totalCleared").value(3));
    }

    // ==================== GET /api/v1/ops/threadpools ====================

    @Test
    @DisplayName("GET /threadpools — 200 + 返回线程池统计")
    void getThreadPoolStatsReturnsStats() throws Exception {
        List<ThreadPoolStatsDTO> statsList = List.of(
                new ThreadPoolStatsDTO("taskExecutor", 4, 8, 2, 5, 100, 150, 300, false, false)
        );
        when(opsService.getThreadPoolStats()).thenReturn(statsList);

        mockMvc.perform(get("/api/v1/ops/threadpools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].poolName").value("taskExecutor"))
                .andExpect(jsonPath("$[0].corePoolSize").value(4))
                .andExpect(jsonPath("$[0].maximumPoolSize").value(8))
                .andExpect(jsonPath("$[0].activeCount").value(2))
                .andExpect(jsonPath("$[0].queueSize").value(5));
    }

    @Test
    @DisplayName("GET /threadpools — 无线程池 -> 200 + 空列表")
    void getThreadPoolStatsReturnsEmptyList() throws Exception {
        when(opsService.getThreadPoolStats()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/ops/threadpools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ==================== GET /api/v1/ops/connection-pools ====================

    @Test
    @DisplayName("GET /connection-pools — 200 + 返回连接池统计")
    void getConnectionPoolStatsReturnsStats() throws Exception {
        ConnectionPoolStatsDTO stats = new ConnectionPoolStatsDTO(
                "nexusPool", 5, 10, 15, 0, 20, 5, 30000, 600000, 1800000, 0);
        when(opsService.getConnectionPoolStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/ops/connection-pools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poolName").value("nexusPool"))
                .andExpect(jsonPath("$.activeConnections").value(5))
                .andExpect(jsonPath("$.idleConnections").value(10))
                .andExpect(jsonPath("$.totalConnections").value(15))
                .andExpect(jsonPath("$.maximumPoolSize").value(20));
    }

    // ==================== GET /api/v1/ops/system ====================

    @Test
    @DisplayName("GET /system — 200 + 返回 JVM 系统信息")
    void getSystemInfoReturnsJvmInfo() throws Exception {
        SystemInfoDTO info = new SystemInfoDTO();
        info.setJvmName("OpenJDK 64-Bit Server VM");
        info.setJvmVersion("17.0.20+8");
        info.setUptime(3600000L);
        info.setHeapUsed(128000000L);
        info.setHeapMax(512000000L);
        info.setHeapUsageRate(0.25);
        info.setAvailableProcessors(8);
        info.setThreadCount(50);
        info.setDaemonThreadCount(20);
        info.setGcStats(List.of(
                new SystemInfoDTO.GcStats("G1 Young Generation", 100, 500, 5.0)
        ));
        when(opsService.getSystemInfo()).thenReturn(info);

        mockMvc.perform(get("/api/v1/ops/system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvmName").value("OpenJDK 64-Bit Server VM"))
                .andExpect(jsonPath("$.jvmVersion").value("17.0.20+8"))
                .andExpect(jsonPath("$.heapUsageRate").value(0.25))
                .andExpect(jsonPath("$.availableProcessors").value(8))
                .andExpect(jsonPath("$.threadCount").value(50))
                .andExpect(jsonPath("$.gcStats[0].name").value("G1 Young Generation"))
                .andExpect(jsonPath("$.gcStats[0].collectionCount").value(100));
    }

    // ==================== GET /api/v1/ops/health/detailed ====================

    @Test
    @DisplayName("GET /health/detailed — 200 + 返回详细健康检查")
    void getDetailedHealthReturnsHealthDetails() throws Exception {
        Map<String, Object> healthDetails = new HashMap<>();
        Map<String, Object> dbHealth = new HashMap<>();
        dbHealth.put("status", "UP");
        dbHealth.put("details", Map.of("database", "MySQL"));
        healthDetails.put("db", dbHealth);

        Map<String, Object> chainHealth = new HashMap<>();
        chainHealth.put("status", "UP");
        chainHealth.put("details", Map.of("rpcUrl", "http://localhost:19585"));
        healthDetails.put("chainNode", chainHealth);

        when(opsService.getDetailedHealth()).thenReturn(healthDetails);

        mockMvc.perform(get("/api/v1/ops/health/detailed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.db.status").value("UP"))
                .andExpect(jsonPath("$.chainNode.status").value("UP"));
    }
}