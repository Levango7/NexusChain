package org.nexus.gateway.developer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DeveloperPortalService} 单元测试 — 覆盖 API 目录、代码示例、
 * SDK 信息、交互式测试和入门指南的核心逻辑。
 */
class DeveloperPortalServiceTest {

    private DeveloperPortalService service;

    @BeforeEach
    void setUp() {
        service = new DeveloperPortalService();
    }

    // ========== API 目录 ==========

    @Test
    @DisplayName("getApiCatalog — 返回非空端点列表")
    void getApiCatalog_returnsNonEmptyList() {
        List<ApiEndpointMeta> catalog = service.getApiCatalog();

        assertNotNull(catalog);
        assertFalse(catalog.isEmpty(), "API 目录不应为空");
        assertTrue(catalog.size() >= 15, "API 目录应包含至少 15 个端点");
    }

    @Test
    @DisplayName("getApiCatalog — 返回不可修改列表")
    void getApiCatalog_returnsUnmodifiableList() {
        List<ApiEndpointMeta> catalog = service.getApiCatalog();

        assertThrows(UnsupportedOperationException.class, () -> catalog.add(null));
    }

    @Test
    @DisplayName("getApiEndpoint — 已知端点返回元数据")
    void getApiEndpoint_knownEndpoint() {
        Optional<ApiEndpointMeta> meta = service.getApiEndpoint("/api/v1/orders");

        assertTrue(meta.isPresent());
        assertEquals("/api/v1/orders", meta.get().getPath());
        assertEquals("POST", meta.get().getMethod());
        assertEquals("v1", meta.get().getVersion());
        assertTrue(meta.get().isAuthRequired());
    }

    @Test
    @DisplayName("getApiEndpoint — 未知端点返回空 Optional")
    void getApiEndpoint_unknownEndpoint() {
        Optional<ApiEndpointMeta> meta = service.getApiEndpoint("/api/v1/nonexistent");

        assertTrue(meta.isEmpty());
    }

    @Test
    @DisplayName("getApiEndpoint — checkout 端点 authRequired=false")
    void getApiEndpoint_checkoutNoAuth() {
        Optional<ApiEndpointMeta> meta = service.getApiEndpoint("/api/v1/checkout/info");

        assertTrue(meta.isPresent());
        assertFalse(meta.get().isAuthRequired(), "checkout 端点不需要认证");
    }

    @Test
    @DisplayName("getApiEndpoint — merchants/register authRequired=false")
    void getApiEndpoint_registerNoAuth() {
        Optional<ApiEndpointMeta> meta = service.getApiEndpoint("/api/v1/merchants/register");

        assertTrue(meta.isPresent());
        assertFalse(meta.get().isAuthRequired(), "注册端点不需要认证");
    }

    @Test
    @DisplayName("getApiEndpoint — v2 端点版本为 v2")
    void getApiEndpoint_v2Version() {
        Optional<ApiEndpointMeta> meta = service.getApiEndpoint("/api/v2/orders");

        assertTrue(meta.isPresent());
        assertEquals("v2", meta.get().getVersion());
    }

    // ========== 代码示例 ==========

    @Test
    @DisplayName("getCodeSample — Java 代码示例")
    void getCodeSample_java() {
        Optional<CodeSample> sample = service.getCodeSample("java", "/api/v1/orders");

        assertTrue(sample.isPresent());
        assertEquals("java", sample.get().getLanguage());
        assertNotNull(sample.get().getCode());
        assertTrue(sample.get().getCode().contains("NexusClient"), "Java 代码应包含 NexusClient");
    }

    @Test
    @DisplayName("getCodeSample — Python 代码示例")
    void getCodeSample_python() {
        Optional<CodeSample> sample = service.getCodeSample("python", "/api/v1/orders");

        assertTrue(sample.isPresent());
        assertEquals("python", sample.get().getLanguage());
        assertTrue(sample.get().getCode().contains("NexusClient"), "Python 代码应包含 NexusClient");
    }

    @Test
    @DisplayName("getCodeSample — JavaScript 代码示例")
    void getCodeSample_javascript() {
        Optional<CodeSample> sample = service.getCodeSample("javascript", "/api/v1/orders");

        assertTrue(sample.isPresent());
        assertEquals("javascript", sample.get().getLanguage());
        assertTrue(sample.get().getCode().contains("NexusClient"), "JS 代码应包含 NexusClient");
    }

    @Test
    @DisplayName("getCodeSample — Go 代码示例")
    void getCodeSample_go() {
        Optional<CodeSample> sample = service.getCodeSample("go", "/api/v1/orders");

        assertTrue(sample.isPresent());
        assertEquals("go", sample.get().getLanguage());
        assertTrue(sample.get().getCode().contains("nexus.NewClient"), "Go 代码应包含 nexus.NewClient");
    }

    @Test
    @DisplayName("getCodeSample — cURL 代码示例")
    void getCodeSample_curl() {
        Optional<CodeSample> sample = service.getCodeSample("curl", "/api/v1/orders");

        assertTrue(sample.isPresent());
        assertEquals("curl", sample.get().getLanguage());
        assertTrue(sample.get().getCode().contains("curl"), "cURL 代码应包含 curl 命令");
    }

    @Test
    @DisplayName("getCodeSample — 不支持的语言返回空 Optional")
    void getCodeSample_unsupportedLanguage() {
        Optional<CodeSample> sample = service.getCodeSample("ruby", "/api/v1/orders");

        assertTrue(sample.isEmpty());
    }

    @Test
    @DisplayName("getCodeSample — 未知端点返回空 Optional")
    void getCodeSample_unknownEndpoint() {
        Optional<CodeSample> sample = service.getCodeSample("java", "/api/v1/nonexistent");

        assertTrue(sample.isEmpty());
    }

    // ========== SDK 信息 ==========

    @Test
    @DisplayName("getAllSdkInfo — 返回 4 种语言的 SDK 信息")
    void getAllSdkInfo_returnsFourLanguages() {
        List<SdkInfo> sdkInfos = service.getAllSdkInfo();

        assertNotNull(sdkInfos);
        assertEquals(4, sdkInfos.size(), "应支持 4 种 SDK 语言");
    }

    @Test
    @DisplayName("getSdkInfo — Java SDK 信息")
    void getSdkInfo_java() {
        Optional<SdkInfo> info = service.getSdkInfo("java");

        assertTrue(info.isPresent());
        assertEquals("java", info.get().getLanguage());
        assertNotNull(info.get().getVersion());
        assertNotNull(info.get().getDownloadUrl());
        assertNotNull(info.get().getInstallCommand());
        assertNotNull(info.get().getChangelogUrl());
        assertFalse(info.get().getDependencies().isEmpty(), "Java SDK 应有依赖列表");
    }

    @Test
    @DisplayName("getSdkInfo — Python SDK 信息")
    void getSdkInfo_python() {
        Optional<SdkInfo> info = service.getSdkInfo("python");

        assertTrue(info.isPresent());
        assertEquals("python", info.get().getLanguage());
        assertTrue(info.get().getInstallCommand().contains("pip"), "Python 安装命令应包含 pip");
    }

    @Test
    @DisplayName("getSdkInfo — JavaScript SDK 信息")
    void getSdkInfo_javascript() {
        Optional<SdkInfo> info = service.getSdkInfo("javascript");

        assertTrue(info.isPresent());
        assertEquals("javascript", info.get().getLanguage());
        assertTrue(info.get().getInstallCommand().contains("npm"), "JS 安装命令应包含 npm");
    }

    @Test
    @DisplayName("getSdkInfo — Go SDK 信息")
    void getSdkInfo_go() {
        Optional<SdkInfo> info = service.getSdkInfo("go");

        assertTrue(info.isPresent());
        assertEquals("go", info.get().getLanguage());
        assertTrue(info.get().getInstallCommand().contains("go get"), "Go 安装命令应包含 go get");
    }

    @Test
    @DisplayName("getSdkInfo — 不支持的语言返回空 Optional")
    void getSdkInfo_unsupportedLanguage() {
        Optional<SdkInfo> info = service.getSdkInfo("ruby");

        assertTrue(info.isEmpty());
    }

    // ========== 交互式测试 ==========

    @Test
    @DisplayName("executeTestRequest — 已知端点返回模拟成功响应")
    void executeTestRequest_knownEndpoint() {
        Map<String, Object> params = Map.of("amount", 1000000);
        Map<String, Object> response = service.executeTestRequest("/api/v1/orders", "POST", params);

        assertNotNull(response);
        assertEquals("success", response.get("status"));
        assertEquals("sandbox", response.get("mode"));
        assertEquals("/api/v1/orders", response.get("endpoint"));
        assertEquals("POST", response.get("method"));
        assertEquals(params, response.get("echoParams"));
    }

    @Test
    @DisplayName("executeTestRequest — 未知端点返回 not_found 错误")
    void executeTestRequest_unknownEndpoint() {
        Map<String, Object> response = service.executeTestRequest("/api/v1/nonexistent", "GET", Map.of());

        assertNotNull(response);
        assertEquals("not_found", response.get("status"));
        assertNotNull(response.get("error"));
    }

    // ========== 测试场景 ==========

    @Test
    @DisplayName("getTestScenarios — 返回非空场景列表")
    void getTestScenarios_returnsNonEmptyList() {
        List<TestScenario> scenarios = service.getTestScenarios();

        assertNotNull(scenarios);
        assertFalse(scenarios.isEmpty(), "测试场景列表不应为空");
        assertTrue(scenarios.size() >= 5, "应包含至少 5 个测试场景");
    }

    @Test
    @DisplayName("getTestScenarios — 返回不可修改列表")
    void getTestScenarios_returnsUnmodifiableList() {
        List<TestScenario> scenarios = service.getTestScenarios();

        assertThrows(UnsupportedOperationException.class, () -> scenarios.add(null));
    }

    @Test
    @DisplayName("getTestScenarios — 每个场景包含必要字段")
    void getTestScenarios_eachScenarioHasRequiredFields() {
        List<TestScenario> scenarios = service.getTestScenarios();

        for (TestScenario scenario : scenarios) {
            assertNotNull(scenario.getName(), "场景名称不能为空");
            assertNotNull(scenario.getDescription(), "场景描述不能为空");
            assertNotNull(scenario.getEndpoint(), "场景端点不能为空");
            assertNotNull(scenario.getMethod(), "场景方法不能为空");
        }
    }

    // ========== 入门指南 ==========

    @Test
    @DisplayName("getQuickstartGuide — 通用指南包含 5 个步骤")
    void getQuickstartGuide_defaultHasFiveSteps() {
        QuickstartGuide guide = service.getQuickstartGuide();

        assertNotNull(guide);
        assertEquals("default", guide.getLanguage());
        assertNotNull(guide.getSteps());
        assertEquals(5, guide.getSteps().size(), "通用入门指南应有 5 个步骤");
    }

    @Test
    @DisplayName("getQuickstartGuide(language) — Java 指南")
    void getQuickstartGuide_java() {
        Optional<QuickstartGuide> guide = service.getQuickstartGuide("java");

        assertTrue(guide.isPresent());
        assertEquals("java", guide.get().getLanguage());
        assertEquals(5, guide.get().getSteps().size(), "Java 入门指南应有 5 个步骤");
    }

    @Test
    @DisplayName("getQuickstartGuide(language) — Python 指南")
    void getQuickstartGuide_python() {
        Optional<QuickstartGuide> guide = service.getQuickstartGuide("python");

        assertTrue(guide.isPresent());
        assertEquals("python", guide.get().getLanguage());
        assertEquals(5, guide.get().getSteps().size());
    }

    @Test
    @DisplayName("getQuickstartGuide(language) — JavaScript 指南")
    void getQuickstartGuide_javascript() {
        Optional<QuickstartGuide> guide = service.getQuickstartGuide("javascript");

        assertTrue(guide.isPresent());
        assertEquals("javascript", guide.get().getLanguage());
        assertEquals(5, guide.get().getSteps().size());
    }

    @Test
    @DisplayName("getQuickstartGuide(language) — Go 指南")
    void getQuickstartGuide_go() {
        Optional<QuickstartGuide> guide = service.getQuickstartGuide("go");

        assertTrue(guide.isPresent());
        assertEquals("go", guide.get().getLanguage());
        assertEquals(5, guide.get().getSteps().size());
    }

    @Test
    @DisplayName("getQuickstartGuide(language) — 不支持的语言返回空 Optional")
    void getQuickstartGuide_unsupportedLanguage() {
        Optional<QuickstartGuide> guide = service.getQuickstartGuide("ruby");

        assertTrue(guide.isEmpty());
    }

    @Test
    @DisplayName("getQuickstartGuide — 步骤序号连续递增")
    void getQuickstartGuide_stepNumbersSequential() {
        QuickstartGuide guide = service.getQuickstartGuide();

        for (int i = 0; i < guide.getSteps().size(); i++) {
            assertEquals(i + 1, guide.getSteps().get(i).getStepNumber(),
                    "步骤序号应从 1 开始连续递增");
        }
    }
}