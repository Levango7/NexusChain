package org.nexus.gateway.developer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 开发者门户 REST API — 提供 API 文档元数据、代码示例、SDK 信息、交互式测试和入门指南。
 *
 * <p>路径前缀：/api/v1/developer</p>
 *
 * <h2>端点概览</h2>
 * <ul>
 *   <li>API 文档元数据：GET /api-catalog, GET /api-catalog/{endpoint}</li>
 *   <li>代码示例：GET /code-samples?language=&endpoint=</li>
 *   <li>SDK 信息：GET /sdk-info, GET /sdk-info/{language}</li>
 *   <li>交互式测试：POST /test-request, GET /test-scenarios</li>
 *   <li>入门指南：GET /quickstart, GET /quickstart/{language}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/developer")
@Tag(name = "Developer Portal", description = "开发者门户：API 文档、代码示例、SDK 信息、交互式测试和入门指南")
public class DeveloperPortalController {

    private static final Logger log = LoggerFactory.getLogger(DeveloperPortalController.class);

    private final DeveloperPortalService developerPortalService;

    public DeveloperPortalController(DeveloperPortalService developerPortalService) {
        this.developerPortalService = developerPortalService;
    }

    // ========== API 文档元数据 ==========

    /**
     * 返回所有 API 端点的元数据列表。
     */
    @Operation(summary = "Get API catalog — all endpoint metadata")
    @GetMapping("/api-catalog")
    public ResponseEntity<List<ApiEndpointMeta>> getApiCatalog() {
        log.debug("Request: GET /api/v1/developer/api-catalog");
        return ResponseEntity.ok(developerPortalService.getApiCatalog());
    }

    /**
     * 返回单个端点的详细文档。
     *
     * @param endpoint 端点路径（如 /api/v1/orders）
     */
    @Operation(summary = "Get detailed documentation for a single endpoint")
    @GetMapping("/api-catalog/{endpoint}")
    public ResponseEntity<ApiEndpointMeta> getApiEndpoint(@PathVariable String endpoint) {
        log.debug("Request: GET /api/v1/developer/api-catalog/{}", endpoint);
        // 路径参数中的 endpoint 可能被 Spring 截断（如 /api/v1/orders → orders），
        // 需要还原为完整路径格式
        String fullEndpoint = normalizeEndpointPath(endpoint);
        Optional<ApiEndpointMeta> meta = developerPortalService.getApiEndpoint(fullEndpoint);
        return meta.map(ResponseEntity::ok)
                .orElseGet(() -> {
                    // 尝试原始路径
                    return developerPortalService.getApiEndpoint(endpoint)
                            .map(ResponseEntity::ok)
                            .orElse(ResponseEntity.notFound().build());
                });
    }

    // ========== 代码示例 ==========

    /**
     * 返回指定语言和端点的代码示例。
     *
     * @param language 编程语言（java, python, javascript, go, curl）
     * @param endpoint 端点路径
     */
    @Operation(summary = "Get code samples for a specific language and endpoint")
    @GetMapping("/code-samples")
    public ResponseEntity<CodeSample> getCodeSamples(
            @RequestParam String language,
            @RequestParam String endpoint) {
        log.debug("Request: GET /api/v1/developer/code-samples?language={}&endpoint={}", language, endpoint);
        Optional<CodeSample> sample = developerPortalService.getCodeSample(language, endpoint);
        return sample.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== SDK 信息 ==========

    /**
     * 返回 SDK 版本信息、下载链接、支持语言列表。
     */
    @Operation(summary = "Get SDK info for all supported languages")
    @GetMapping("/sdk-info")
    public ResponseEntity<List<SdkInfo>> getSdkInfo() {
        log.debug("Request: GET /api/v1/developer/sdk-info");
        return ResponseEntity.ok(developerPortalService.getAllSdkInfo());
    }

    /**
     * 返回指定语言的 SDK 详细信息。
     *
     * @param language 编程语言
     */
    @Operation(summary = "Get SDK info for a specific language")
    @GetMapping("/sdk-info/{language}")
    public ResponseEntity<SdkInfo> getSdkInfoByLanguage(@PathVariable String language) {
        log.debug("Request: GET /api/v1/developer/sdk-info/{}", language);
        Optional<SdkInfo> info = developerPortalService.getSdkInfo(language);
        return info.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 交互式测试 ==========

    /**
     * 代理执行测试请求（仅在 sandbox 模式可用）。
     *
     * <p>传入 endpoint + params，返回模拟响应。不会创建真实交易。</p>
     */
    @Operation(summary = "Execute a test request in sandbox mode")
    @PostMapping("/test-request")
    public ResponseEntity<Map<String, Object>> executeTestRequest(@RequestBody TestRequestDto request) {
        log.info("Sandbox test request: {} {} params={}", request.getMethod(), request.getEndpoint(), request.getParams());
        Map<String, Object> response = developerPortalService.executeTestRequest(
                request.getEndpoint(), request.getMethod(), request.getParams());
        return ResponseEntity.ok(response);
    }

    /**
     * 返回预置测试场景列表。
     */
    @Operation(summary = "Get preset test scenarios")
    @GetMapping("/test-scenarios")
    public ResponseEntity<List<TestScenario>> getTestScenarios() {
        log.debug("Request: GET /api/v1/developer/test-scenarios");
        return ResponseEntity.ok(developerPortalService.getTestScenarios());
    }

    // ========== 入门指南 ==========

    /**
     * 返回快速入门指南（通用步骤化集成流程）。
     */
    @Operation(summary = "Get quickstart guide (general)")
    @GetMapping("/quickstart")
    public ResponseEntity<QuickstartGuide> getQuickstart() {
        log.debug("Request: GET /api/v1/developer/quickstart");
        QuickstartGuide guide = developerPortalService.getQuickstartGuide();
        return ResponseEntity.ok(guide);
    }

    /**
     * 返回指定语言的快速入门指南。
     *
     * @param language 编程语言
     */
    @Operation(summary = "Get quickstart guide for a specific language")
    @GetMapping("/quickstart/{language}")
    public ResponseEntity<QuickstartGuide> getQuickstartByLanguage(@PathVariable String language) {
        log.debug("Request: GET /api/v1/developer/quickstart/{}", language);
        Optional<QuickstartGuide> guide = developerPortalService.getQuickstartGuide(language);
        return guide.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ========== 内部工具 ==========

    /**
     * 将路径变量中的 endpoint 还原为完整路径。
     *
     * <p>Spring MVC 的 @PathVariable 会去掉前导斜杠，且分段匹配可能截断路径。
     * 此方法尝试将传入的 endpoint 还原为 /api/v1/... 或 /api/v2/... 格式。</p>
     */
    private String normalizeEndpointPath(String endpoint) {
        if (endpoint == null || endpoint.isEmpty()) {
            return endpoint;
        }
        // 如果已经是完整路径，直接返回
        if (endpoint.startsWith("/api/")) {
            return endpoint;
        }
        // 尝试添加 /api/v1/ 前缀
        String withV1 = "/api/v1/" + endpoint;
        if (developerPortalService.getApiEndpoint(withV1).isPresent()) {
            return withV1;
        }
        // 尝试添加 /api/v2/ 前缀
        String withV2 = "/api/v2/" + endpoint;
        if (developerPortalService.getApiEndpoint(withV2).isPresent()) {
            return withV2;
        }
        // 无法还原，返回原始值
        return endpoint;
    }

    // ========== 请求 DTO ==========

    /**
     * 测试请求 DTO — 用于交互式测试端点。
     */
    public static class TestRequestDto {
        private String endpoint;
        private String method;
        private Map<String, Object> params;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> params) { this.params = params; }
    }
}