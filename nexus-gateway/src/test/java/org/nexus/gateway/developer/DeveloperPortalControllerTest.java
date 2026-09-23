package org.nexus.gateway.developer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link DeveloperPortalController} Web 层测试 — 使用 MockMvc standalone 模式，
 * 验证各端点的 HTTP 响应状态码和 JSON 结构。
 */
class DeveloperPortalControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DeveloperPortalService service = new DeveloperPortalService();
        DeveloperPortalController controller = new DeveloperPortalController(service);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ========== API 文档元数据 ==========

    @Test
    @DisplayName("GET /api/v1/developer/api-catalog — 200 返回端点列表")
    void getApiCatalog_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/developer/api-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].path").exists())
                .andExpect(jsonPath("$[0].method").exists())
                .andExpect(jsonPath("$[0].summary").exists())
                .andExpect(jsonPath("$[0].version").exists());
    }

    @Test
    @DisplayName("GET /api/v1/developer/api-catalog/{endpoint} — 已知端点 200")
    void getApiEndpoint_knownEndpoint() throws Exception {
        // 使用完整路径作为 endpoint 参数（Spring 会将 /api/v1/orders 编码为路径段）
        mockMvc.perform(get("/api/v1/developer/api-catalog//api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/api/v1/orders"))
                .andExpect(jsonPath("$.method").value("POST"));
    }

    @Test
    @DisplayName("GET /api/v1/developer/api-catalog/{endpoint} — 未知端点 404")
    void getApiEndpoint_unknownEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/developer/api-catalog/nonexistent"))
                .andExpect(status().isNotFound());
    }

    // ========== 代码示例 ==========

    @Test
    @DisplayName("GET /code-samples?language=java&endpoint=/api/v1/orders — 200")
    void getCodeSamples_java() throws Exception {
        mockMvc.perform(get("/api/v1/developer/code-samples")
                        .param("language", "java")
                        .param("endpoint", "/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    @DisplayName("GET /code-samples?language=python&endpoint=/api/v1/orders — 200")
    void getCodeSamples_python() throws Exception {
        mockMvc.perform(get("/api/v1/developer/code-samples")
                        .param("language", "python")
                        .param("endpoint", "/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("python"));
    }

    @Test
    @DisplayName("GET /code-samples?language=curl&endpoint=/api/v1/orders — 200")
    void getCodeSamples_curl() throws Exception {
        mockMvc.perform(get("/api/v1/developer/code-samples")
                        .param("language", "curl")
                        .param("endpoint", "/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("curl"));
    }

    @Test
    @DisplayName("GET /code-samples?language=ruby — 不支持的语言 404")
    void getCodeSamples_unsupportedLanguage() throws Exception {
        mockMvc.perform(get("/api/v1/developer/code-samples")
                        .param("language", "ruby")
                        .param("endpoint", "/api/v1/orders"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /code-samples?language=java&endpoint=/api/v1/nonexistent — 未知端点 404")
    void getCodeSamples_unknownEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/developer/code-samples")
                        .param("language", "java")
                        .param("endpoint", "/api/v1/nonexistent"))
                .andExpect(status().isNotFound());
    }

    // ========== SDK 信息 ==========

    @Test
    @DisplayName("GET /sdk-info — 200 返回 SDK 列表")
    void getSdkInfo_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/developer/sdk-info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].language").exists())
                .andExpect(jsonPath("$[0].version").exists())
                .andExpect(jsonPath("$[0].downloadUrl").exists())
                .andExpect(jsonPath("$[0].installCommand").exists());
    }

    @Test
    @DisplayName("GET /sdk-info/java — 200 返回 Java SDK 详情")
    void getSdkInfoByLanguage_java() throws Exception {
        mockMvc.perform(get("/api/v1/developer/sdk-info/java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.downloadUrl").exists());
    }

    @Test
    @DisplayName("GET /sdk-info/python — 200 返回 Python SDK 详情")
    void getSdkInfoByLanguage_python() throws Exception {
        mockMvc.perform(get("/api/v1/developer/sdk-info/python"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("python"));
    }

    @Test
    @DisplayName("GET /sdk-info/ruby — 不支持的语言 404")
    void getSdkInfoByLanguage_unsupported() throws Exception {
        mockMvc.perform(get("/api/v1/developer/sdk-info/ruby"))
                .andExpect(status().isNotFound());
    }

    // ========== 交互式测试 ==========

    @Test
    @DisplayName("POST /test-request — 已知端点返回模拟响应")
    void executeTestRequest_knownEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/developer/test-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"/api/v1/orders\",\"method\":\"POST\",\"params\":{\"amount\":1000000}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.mode").value("sandbox"))
                .andExpect(jsonPath("$.endpoint").value("/api/v1/orders"));
    }

    @Test
    @DisplayName("POST /test-request — 未知端点返回 not_found")
    void executeTestRequest_unknownEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/developer/test-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"/api/v1/nonexistent\",\"method\":\"GET\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    @Test
    @DisplayName("GET /test-scenarios — 200 返回场景列表")
    void getTestScenarios_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/developer/test-scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].endpoint").exists())
                .andExpect(jsonPath("$[0].method").exists());
    }

    // ========== 入门指南 ==========

    @Test
    @DisplayName("GET /quickstart — 200 返回通用入门指南")
    void getQuickstart_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/developer/quickstart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("default"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps[0].stepNumber").value(1))
                .andExpect(jsonPath("$.steps[0].title").exists());
    }

    @Test
    @DisplayName("GET /quickstart/java — 200 返回 Java 入门指南")
    void getQuickstartByLanguage_java() throws Exception {
        mockMvc.perform(get("/api/v1/developer/quickstart/java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("java"))
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.steps[0].stepNumber").value(1));
    }

    @Test
    @DisplayName("GET /quickstart/python — 200 返回 Python 入门指南")
    void getQuickstartByLanguage_python() throws Exception {
        mockMvc.perform(get("/api/v1/developer/quickstart/python"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("python"));
    }

    @Test
    @DisplayName("GET /quickstart/javascript — 200 返回 JS 入门指南")
    void getQuickstartByLanguage_javascript() throws Exception {
        mockMvc.perform(get("/api/v1/developer/quickstart/javascript"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("javascript"));
    }

    @Test
    @DisplayName("GET /quickstart/go — 200 返回 Go 入门指南")
    void getQuickstartByLanguage_go() throws Exception {
        mockMvc.perform(get("/api/v1/developer/quickstart/go"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.language").value("go"));
    }

    @Test
    @DisplayName("GET /quickstart/ruby — 不支持的语言 404")
    void getQuickstartByLanguage_unsupported() throws Exception {
        mockMvc.perform(get("/api/v1/developer/quickstart/ruby"))
                .andExpect(status().isNotFound());
    }
}