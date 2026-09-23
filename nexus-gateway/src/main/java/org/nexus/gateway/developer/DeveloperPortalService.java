package org.nexus.gateway.developer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 开发者门户服务 — 提供 API 目录、代码示例、SDK 信息、测试场景和入门指南。
 *
 * <p>API 端点元数据硬编码在 Service 中（API 变化不频繁），
 * 代码示例基于模板字符串生成，测试请求仅在 sandbox 模式下代理执行。</p>
 */
@Service
public class DeveloperPortalService {

    private static final Logger log = LoggerFactory.getLogger(DeveloperPortalService.class);

    /** 支持的代码示例语言 */
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("java", "python", "javascript", "go", "curl");

    /** 支持的 SDK 语言 */
    private static final Set<String> SUPPORTED_SDK_LANGUAGES = Set.of("java", "python", "javascript", "go");

    // --- API 端点元数据目录 ---

    private final List<ApiEndpointMeta> apiCatalog;

    // --- SDK 信息 ---

    private final Map<String, SdkInfo> sdkInfoMap;

    // --- 测试场景 ---

    private final List<TestScenario> testScenarios;

    // --- 快速入门指南 ---

    private final Map<String, QuickstartGuide> quickstartGuides;

    public DeveloperPortalService() {
        this.apiCatalog = initApiCatalog();
        this.sdkInfoMap = initSdkInfo();
        this.testScenarios = initTestScenarios();
        this.quickstartGuides = initQuickstartGuides();
    }

    // ========== API 目录 ==========

    /**
     * 返回所有 API 端点的元数据列表。
     */
    public List<ApiEndpointMeta> getApiCatalog() {
        return Collections.unmodifiableList(apiCatalog);
    }

    /**
     * 返回单个端点的详细文档。按路径匹配（如 /api/v1/orders）。
     *
     * @param endpoint 端点路径
     * @return 端点元数据；未找到返回空 Optional
     */
    public Optional<ApiEndpointMeta> getApiEndpoint(String endpoint) {
        return apiCatalog.stream()
                .filter(meta -> meta.getPath().equals(endpoint))
                .findFirst();
    }

    // ========== 代码示例 ==========

    /**
     * 返回指定语言和端点的代码示例。
     *
     * @param language 编程语言（java, python, javascript, go, curl）
     * @param endpoint 端点路径
     * @return 代码示例；不支持的语言返回空 Optional
     */
    public Optional<CodeSample> getCodeSample(String language, String endpoint) {
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            log.warn("Unsupported code sample language: {}", language);
            return Optional.empty();
        }
        String code = generateCodeTemplate(language, endpoint);
        if (code == null) {
            return Optional.empty();
        }
        String description = String.format("%s code sample for %s", language, endpoint);
        return Optional.of(new CodeSample(language, code, description));
    }

    /**
     * 基于模板生成代码示例。
     */
    private String generateCodeTemplate(String language, String endpoint) {
        // 查找端点元数据以获取 HTTP 方法
        Optional<ApiEndpointMeta> metaOpt = getApiEndpoint(endpoint);
        if (metaOpt.isEmpty()) {
            return null;
        }
        ApiEndpointMeta meta = metaOpt.get();
        String method = meta.getMethod().toLowerCase();

        return switch (language) {
            case "java" -> generateJavaCode(endpoint, method);
            case "python" -> generatePythonCode(endpoint, method);
            case "javascript" -> generateJavaScriptCode(endpoint, method);
            case "go" -> generateGoCode(endpoint, method);
            case "curl" -> generateCurlCode(endpoint, method);
            default -> null;
        };
    }

    private String generateJavaCode(String endpoint, String method) {
        return """
                // Java — 使用 NexusChain SDK 调用 %s %s
                import org.nexus.sdk.NexusClient;
                import org.nexus.sdk.model.*;

                NexusClient client = NexusClient.builder()
                        .apiKey("YOUR_API_KEY")
                        .baseUrl("https://api.nexuschain.io")
                        .build();

                %s response = client.%s("%s");
                System.out.println(response);
                """.formatted(
                method.toUpperCase(), endpoint,
                "POST".equalsIgnoreCase(method) ? "OrderResponse" : "ApiResponse",
                method.equals("post") ? "createOrder" : method.equals("get") ? "getOrder" : "executeRequest",
                endpoint);
    }

    private String generatePythonCode(String endpoint, String method) {
        return """
                # Python — 使用 nexuschain-sdk 调用 %s %s
                from nexuschain import NexusClient

                client = NexusClient(
                    api_key="YOUR_API_KEY",
                    base_url="https://api.nexuschain.io"
                )

                response = client.%s("%s")
                print(response)
                """.formatted(
                method.toUpperCase(), endpoint,
                method.equals("post") ? "create_order" : method.equals("get") ? "get_order" : "request",
                endpoint);
    }

    private String generateJavaScriptCode(String endpoint, String method) {
        return """
                // JavaScript — 使用 @nexuschain/sdk 调用 %s %s
                import { NexusClient } from '@nexuschain/sdk';

                const client = new NexusClient({
                    apiKey: 'YOUR_API_KEY',
                    baseUrl: 'https://api.nexuschain.io'
                });

                const response = await client.%s('%s');
                console.log(response);
                """.formatted(
                method.toUpperCase(), endpoint,
                method.equals("post") ? "createOrder" : method.equals("get") ? "getOrder" : "request",
                endpoint);
    }

    private String generateGoCode(String endpoint, String method) {
        return """
                // Go — 使用 nexuschain-go-sdk 调用 %s %s
                package main

                import (
                    "fmt"
                    "github.com/nexuschain/go-sdk/nexus"
                )

                func main() {
                    client := nexus.NewClient("YOUR_API_KEY", "https://api.nexuschain.io")
                    resp, err := client.%s("%s")
                    if err != nil {
                        panic(err)
                    }
                    fmt.Println(resp)
                }
                """.formatted(
                method.toUpperCase(), endpoint,
                method.equals("post") ? "CreateOrder" : method.equals("get") ? "GetOrder" : "Request",
                endpoint);
    }

    private String generateCurlCode(String endpoint, String method) {
        return """
                # cURL — 调用 %s %s
                curl -X %s \\
                  https://api.nexuschain.io%s \\
                  -H "X-NexusChain-ApiKey: YOUR_API_KEY" \\
                  -H "Content-Type: application/json" \\
                  -d '{"amount": 1000000, "description": "Test order"}'
                """.formatted(method.toUpperCase(), endpoint, method.toUpperCase(), endpoint);
    }

    // ========== SDK 信息 ==========

    /**
     * 返回所有支持的 SDK 信息列表。
     */
    public List<SdkInfo> getAllSdkInfo() {
        return List.copyOf(sdkInfoMap.values());
    }

    /**
     * 返回指定语言的 SDK 详细信息。
     *
     * @param language 编程语言
     * @return SDK 信息；不支持的语言返回空 Optional
     */
    public Optional<SdkInfo> getSdkInfo(String language) {
        if (!SUPPORTED_SDK_LANGUAGES.contains(language)) {
            log.warn("Unsupported SDK language: {}", language);
            return Optional.empty();
        }
        return Optional.ofNullable(sdkInfoMap.get(language));
    }

    // ========== 交互式测试 ==========

    /**
     * 代理执行测试请求（仅在 sandbox 模式可用）。
     *
     * <p>返回模拟响应而非真实调用后端服务，确保开发者可以安全地试用 API。</p>
     *
     * @param endpoint 目标端点
     * @param method   HTTP 方法
     * @param params   请求参数
     * @return 模拟响应
     */
    public Map<String, Object> executeTestRequest(String endpoint, String method, Map<String, Object> params) {
        log.info("Sandbox test request: {} {} params={}", method, endpoint, params);

        // 验证端点存在
        Optional<ApiEndpointMeta> metaOpt = getApiEndpoint(endpoint);
        if (metaOpt.isEmpty()) {
            return Map.of("error", "Unknown endpoint: " + endpoint, "status", "not_found");
        }

        // 返回模拟响应
        Map<String, Object> mockResponse = new LinkedHashMap<>();
        mockResponse.put("status", "success");
        mockResponse.put("endpoint", endpoint);
        mockResponse.put("method", method);
        mockResponse.put("mode", "sandbox");
        mockResponse.put("message", "This is a simulated response for testing purposes. No real transaction was created.");
        mockResponse.put("echoParams", params);
        return mockResponse;
    }

    /**
     * 返回预置测试场景列表。
     */
    public List<TestScenario> getTestScenarios() {
        return Collections.unmodifiableList(testScenarios);
    }

    // ========== 快速入门指南 ==========

    /**
     * 返回默认（通用）快速入门指南。
     */
    public QuickstartGuide getQuickstartGuide() {
        return quickstartGuides.get("default");
    }

    /**
     * 返回指定语言的快速入门指南。
     *
     * @param language 编程语言
     * @return 入门指南；不支持的语言返回空 Optional
     */
    public Optional<QuickstartGuide> getQuickstartGuide(String language) {
        if (!SUPPORTED_SDK_LANGUAGES.contains(language)) {
            log.warn("Unsupported quickstart language: {}", language);
            return Optional.empty();
        }
        return Optional.ofNullable(quickstartGuides.get(language));
    }

    // ========== 初始化方法 ==========

    private List<ApiEndpointMeta> initApiCatalog() {
        List<ApiEndpointMeta> catalog = new ArrayList<>();

        // --- 订单 API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v1/orders", "POST", "Create a new payment order",
                "创建新的支付订单，返回订单实体。订单归属以认证上下文为准，请求体中的 merchantId 被忽略并覆盖。",
                "v1", true,
                List.of(
                        Map.of("name", "merchantId", "type", "string", "required", false, "description", "商户ID（由认证上下文自动填充）"),
                        Map.of("name", "amount", "type", "number", "required", true, "description", "订单金额（最小单位）"),
                        Map.of("name", "description", "type", "string", "required", false, "description", "订单描述"),
                        Map.of("name", "notifyUrl", "type", "string", "required", false, "description", "支付通知回调URL")
                ),
                Map.of("201", Map.of("description", "订单创建成功", "schema", "PaymentOrder")),
                Map.of("400", "Bad Request — 参数校验失败", "403", "Forbidden — 商户归属校验失败")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/orders/{id}", "GET", "Query an order by ID",
                "根据订单ID查询订单详情。仅允许查询属于认证商户的订单。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订单ID（路径参数）")
                ),
                Map.of("200", Map.of("description", "订单详情", "schema", "PaymentOrder"),
                        "404", Map.of("description", "订单不存在")),
                Map.of("403", "Forbidden — 跨商户访问被拒绝", "404", "Not Found — 订单不存在")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/orders/{id}/pay", "POST", "Initiate payment for an order",
                "对指定订单发起支付，返回支付结果和收银台URL。仅允许对属于认证商户的订单发起支付。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订单ID（路径参数）"),
                        Map.of("name", "payerAddress", "type", "string", "required", true, "description", "付款方钱包地址")
                ),
                Map.of("200", Map.of("description", "支付结果", "schema", "PaymentResult")),
                Map.of("403", "Forbidden — 跨商户操作被拒绝", "404", "Not Found — 订单不存在")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/orders/{id}/confirm", "POST", "Confirm payment after chain event",
                "在收到链上事件或手动回调后确认支付。传入链上交易哈希进行确认。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订单ID（路径参数）"),
                        Map.of("name", "chainTxHash", "type", "string", "required", true, "description", "链上交易哈希")
                ),
                Map.of("200", Map.of("description", "支付确认结果", "schema", "PaymentResult")),
                Map.of("403", "Forbidden — 跨商户操作被拒绝", "404", "Not Found — 订单不存在")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/orders/{id}/refund", "POST", "Initiate a refund",
                "对已支付的订单发起退款。退款金额不能超过订单金额。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订单ID（路径参数）"),
                        Map.of("name", "amount", "type", "number", "required", true, "description", "退款金额"),
                        Map.of("name", "reason", "type", "string", "required", false, "description", "退款原因")
                ),
                Map.of("201", Map.of("description", "退款创建成功", "schema", "Refund")),
                Map.of("403", "Forbidden — 跨商户操作被拒绝", "404", "Not Found — 订单不存在")
        ));

        // --- 退款审批 API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v1/refunds", "POST", "Request a refund",
                "发起退款请求，进入退款审批流程。目标订单必须属于认证商户。",
                "v1", true,
                List.of(
                        Map.of("name", "orderId", "type", "integer", "required", true, "description", "订单ID"),
                        Map.of("name", "amount", "type", "number", "required", true, "description", "退款金额"),
                        Map.of("name", "reason", "type", "string", "required", false, "description", "退款原因")
                ),
                Map.of("201", Map.of("description", "退款请求创建成功", "schema", "RefundRequest")),
                Map.of("400", "Bad Request — 参数缺失或无效", "403", "Forbidden — 商户归属校验失败")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/refunds/approve", "POST", "Approve or reject a refund",
                "审批或拒绝退款请求。审批人身份从认证上下文获取。",
                "v1", true,
                List.of(
                        Map.of("name", "refundId", "type", "integer", "required", true, "description", "退款ID"),
                        Map.of("name", "approved", "type", "boolean", "required", true, "description", "是否批准"),
                        Map.of("name", "reason", "type", "string", "required", false, "description", "拒绝原因（approved=false时）")
                ),
                Map.of("200", Map.of("description", "审批结果", "schema", "RefundRequest")),
                Map.of("400", "Bad Request — refundId 缺失或无效", "403", "Forbidden — 退款单归属校验失败")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/refunds/{id}/execute", "POST", "Execute an approved refund",
                "执行已审批通过的退款。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "退款ID（路径参数）")
                ),
                Map.of("200", Map.of("description", "退款执行结果", "schema", "RefundRequest")),
                Map.of("403", "Forbidden — 退款单归属校验失败", "404", "Not Found — 退款单不存在")
        ));

        // --- 订阅 API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v1/subscriptions", "POST", "Create a new subscription agreement",
                "创建新的订阅协议，设定定期扣款的付款方、收款方、金额和周期。merchantId 由认证上下文自动填充。",
                "v1", true,
                List.of(
                        Map.of("name", "payerAddress", "type", "string", "required", true, "description", "付款方钱包地址"),
                        Map.of("name", "payeeAddress", "type", "string", "required", true, "description", "收款方钱包地址"),
                        Map.of("name", "amount", "type", "number", "required", true, "description", "每期扣款金额"),
                        Map.of("name", "cycleDays", "type", "integer", "required", true, "description", "扣款周期（天）")
                ),
                Map.of("201", Map.of("description", "订阅创建成功", "schema", "Subscription")),
                Map.of("400", "Bad Request — 参数校验失败", "403", "Forbidden — 跨商户创建被拒绝")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/subscriptions/{id}", "GET", "Query a subscription by ID",
                "根据订阅ID查询订阅详情。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订阅ID（路径参数）")
                ),
                Map.of("200", Map.of("description", "订阅详情", "schema", "Subscription"),
                        "404", Map.of("description", "订阅不存在")),
                Map.of("403", "Forbidden — 跨商户访问被拒绝", "404", "Not Found — 订阅不存在")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/subscriptions/{id}/charge", "POST", "Manually trigger a recurring charge",
                "手动触发订阅的定期扣款。返回链上交易哈希或扣款失败信息。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订阅ID（路径参数）")
                ),
                Map.of("200", Map.of("description", "扣款成功", "schema", "ChargeResponse"),
                        "409", Map.of("description", "扣款失败")),
                Map.of("403", "Forbidden — 跨商户操作被拒绝", "404", "Not Found — 订阅不存在")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/subscriptions/{id}/cancel", "POST", "Cancel an active subscription",
                "取消活跃的订阅协议。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "订阅ID（路径参数）")
                ),
                Map.of("200", Map.of("description", "订阅取消结果", "schema", "Subscription")),
                Map.of("403", "Forbidden — 跨商户操作被拒绝", "404", "Not Found — 订阅不存在")
        ));

        // --- 商户管理 API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v1/merchants/register", "POST", "Register a new merchant",
                "注册新商户。这是入驻入口，无需认证。",
                "v1", false,
                List.of(
                        Map.of("name", "merchantName", "type", "string", "required", true, "description", "商户名称"),
                        Map.of("name", "email", "type", "string", "required", true, "description", "联系邮箱"),
                        Map.of("name", "settlementAddress", "type", "string", "required", true, "description", "结算钱包地址")
                ),
                Map.of("201", Map.of("description", "商户注册成功", "schema", "Merchant")),
                Map.of("400", "Bad Request — 参数校验失败")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/merchants/{id}", "GET", "Query merchant details",
                "根据商户ID查询商户详情。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "商户ID（路径参数）")
                ),
                Map.of("200", Map.of("description", "商户详情", "schema", "Merchant"),
                        "404", Map.of("description", "商户不存在")),
                Map.of("404", "Not Found — 商户不存在")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/merchants/{id}/api-keys", "POST", "Generate a new API key pair",
                "为商户生成新的 API Key 对（apiKey + secret）。仅管理员可操作。secret 仅展示一次。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "商户ID（路径参数）")
                ),
                Map.of("201", Map.of("description", "API Key 生成成功")),
                Map.of("403", "Forbidden — 需要管理员权限")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/merchants/{id}/api-keys", "DELETE", "Revoke an API key",
                "撤销商户的 API Key。仅管理员可操作。",
                "v1", true,
                List.of(
                        Map.of("name", "id", "type", "integer", "required", true, "description", "商户ID（路径参数）"),
                        Map.of("name", "apiKey", "type", "string", "required", true, "description", "要撤销的 API Key")
                ),
                Map.of("204", Map.of("description", "API Key 撤销成功")),
                Map.of("403", "Forbidden — 需要管理员权限")
        ));

        // --- Webhook API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v1/webhooks/chain-events", "POST", "Handle chain event callback",
                "NexusChain 内部链事件回调端点。通过 HMAC-SHA256 签名验证回调真实性。",
                "v1", false,
                List.of(
                        Map.of("name", "orderId", "type", "integer", "required", true, "description", "订单ID"),
                        Map.of("name", "chainTxHash", "type", "string", "required", true, "description", "链上交易哈希"),
                        Map.of("name", "eventType", "type", "string", "required", true, "description", "事件类型（如 PAYMENT_CONFIRMED）")
                ),
                Map.of("200", Map.of("description", "回调处理成功"),
                        "401", Map.of("description", "签名验证失败")),
                Map.of("401", "Unauthorized — 签名验证失败", "400", "Bad Request — 缺少必要字段")
        ));

        // --- Checkout API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v1/checkout/{token}", "GET", "Cashier checkout redirect",
                "根据 checkout token 重定向到收银台页面完成支付。面向付款方，无需认证。",
                "v1", false,
                List.of(
                        Map.of("name", "token", "type", "string", "required", true, "description", "Checkout token（路径参数）")
                ),
                Map.of("302", Map.of("description", "重定向到收银台页面"),
                        "404", Map.of("description", "无效或过期的 token")),
                Map.of("404", "Not Found — 无效或过期的 checkout token")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/checkout/info", "GET", "Get order info by checkout token",
                "收银台页面加载时获取订单信息。面向付款方，无需认证。",
                "v1", false,
                List.of(
                        Map.of("name", "token", "type", "string", "required", true, "description", "Checkout token（查询参数）")
                ),
                Map.of("200", Map.of("description", "订单信息"),
                        "404", Map.of("description", "无效或过期的 token")),
                Map.of("404", "Not Found — 无效或过期的 checkout token")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v1/checkout/status", "GET", "Poll payment status by checkout token",
                "收银台页面轮询支付状态。面向付款方，无需认证。",
                "v1", false,
                List.of(
                        Map.of("name", "token", "type", "string", "required", true, "description", "Checkout token（查询参数）")
                ),
                Map.of("200", Map.of("description", "支付状态信息"),
                        "404", Map.of("description", "无效或过期的 token")),
                Map.of("404", "Not Found — 无效或过期的 checkout token")
        ));

        // --- v2 订单 API ---
        catalog.add(new ApiEndpointMeta(
                "/api/v2/orders", "POST", "Create order (v2)",
                "v2 创建订单，响应包裹在统一信封内，错误时返回 V2ErrorResponse。",
                "v2", true,
                List.of(
                        Map.of("name", "merchantId", "type", "string", "required", false, "description", "商户ID（由认证上下文自动填充）"),
                        Map.of("name", "amount", "type", "number", "required", true, "description", "订单金额"),
                        Map.of("name", "description", "type", "string", "required", false, "description", "订单描述")
                ),
                Map.of("201", Map.of("description", "订单创建成功", "schema", "PaymentOrder")),
                Map.of("400", "Bad Request — 参数校验失败", "403", "Forbidden — 商户归属校验失败")
        ));

        catalog.add(new ApiEndpointMeta(
                "/api/v2/orders", "GET", "List orders (v2, cursor pagination)",
                "v2 订单列表，支持游标分页和字段筛选。按 id 升序，游标为上一页最后一项的 id（base64 编码）。",
                "v2", true,
                List.of(
                        Map.of("name", "cursor", "type", "string", "required", false, "description", "分页游标"),
                        Map.of("name", "pageSize", "type", "integer", "required", false, "description", "每页条数（默认20，最大100）"),
                        Map.of("name", "fields", "type", "string", "required", false, "description", "字段筛选（如 id,amount,status）")
                ),
                Map.of("200", Map.of("description", "订单列表（分页）")),
                Map.of("400", "Bad Request — 游标或字段无效")
        ));

        return Collections.unmodifiableList(catalog);
    }

    private Map<String, SdkInfo> initSdkInfo() {
        Map<String, SdkInfo> map = new LinkedHashMap<>();

        map.put("java", new SdkInfo(
                "java", "1.2.0",
                "https://repo1.maven.org/maven2/org/nexus/nexuschain-sdk/1.2.0/nexuschain-sdk-1.2.0.jar",
                "implementation 'org.nexus:nexuschain-sdk:1.2.0'",
                List.of("com.fasterxml.jackson.core:jackson-databind:2.15+", "org.slf4j:slf4j-api:1.7+"),
                "https://github.com/nexuschain/nexuschain-sdk-java/blob/main/CHANGELOG.md"
        ));

        map.put("python", new SdkInfo(
                "python", "1.1.3",
                "https://pypi.org/project/nexuschain-sdk/1.1.3/",
                "pip install nexuschain-sdk==1.1.3",
                List.of("requests>=2.28", "pydantic>=2.0"),
                "https://github.com/nexuschain/nexuschain-sdk-python/blob/main/CHANGELOG.md"
        ));

        map.put("javascript", new SdkInfo(
                "javascript", "1.3.1",
                "https://www.npmjs.com/package/@nexuschain/sdk",
                "npm install @nexuschain/sdk@1.3.1",
                List.of("node-fetch >= 3.0 (Node.js < 18)"),
                "https://github.com/nexuschain/nexuschain-sdk-js/blob/main/CHANGELOG.md"
        ));

        map.put("go", new SdkInfo(
                "go", "0.9.0",
                "https://github.com/nexuschain/go-sdk/releases/tag/v0.9.0",
                "go get github.com/nexuschain/go-sdk@v0.9.0",
                List.of(),
                "https://github.com/nexuschain/go-sdk/blob/main/CHANGELOG.md"
        ));

        return Collections.unmodifiableMap(map);
    }

    private List<TestScenario> initTestScenarios() {
        List<TestScenario> scenarios = new ArrayList<>();

        scenarios.add(new TestScenario(
                "Create Order", "创建一个测试订单",
                "/api/v1/orders", "POST",
                Map.of("amount", 1000000, "description", "Test order from developer portal"),
                Map.of("status", "success", "orderId", "mock-001")
        ));

        scenarios.add(new TestScenario(
                "Query Order", "查询订单状态",
                "/api/v1/orders/1", "GET",
                Map.of(),
                Map.of("status", "success", "orderNo", "NEX-TEST-001", "amount", 1000000)
        ));

        scenarios.add(new TestScenario(
                "Initiate Payment", "发起支付",
                "/api/v1/orders/1/pay", "POST",
                Map.of("payerAddress", "0x1234567890abcdef"),
                Map.of("status", "success", "checkoutUrl", "https://api.nexuschain.io/checkout/test-token")
        ));

        scenarios.add(new TestScenario(
                "Create Subscription", "创建订阅协议",
                "/api/v1/subscriptions", "POST",
                Map.of("payerAddress", "0x1234...", "payeeAddress", "0x5678...",
                        "amount", 500000, "cycleDays", 30),
                Map.of("status", "success", "subscriptionId", "mock-sub-001")
        ));

        scenarios.add(new TestScenario(
                "Request Refund", "发起退款请求",
                "/api/v1/refunds", "POST",
                Map.of("orderId", 1, "amount", 500000, "reason", "Customer request"),
                Map.of("status", "success", "refundId", "mock-ref-001")
        ));

        scenarios.add(new TestScenario(
                "Webhook Chain Event", "模拟链事件回调",
                "/api/v1/webhooks/chain-events", "POST",
                Map.of("orderId", 1, "chainTxHash", "0xabc...", "eventType", "PAYMENT_CONFIRMED"),
                Map.of("status", "success", "message", "Payment confirmed")
        ));

        return Collections.unmodifiableList(scenarios);
    }

    private Map<String, QuickstartGuide> initQuickstartGuides() {
        Map<String, QuickstartGuide> guides = new LinkedHashMap<>();

        // 通用入门指南
        guides.put("default", new QuickstartGuide("default", List.of(
                new QuickstartStep(1, "Register a Merchant",
                        "注册商户账号，获取商户ID和初始API Key。访问 /api/v1/merchants/register 完成注册。",
                        """
                                curl -X POST https://api.nexuschain.io/api/v1/merchants/register \\
                                  -H "Content-Type: application/json" \\
                                  -d '{"merchantName":"My Store","email":"owner@mystore.com","settlementAddress":"0x..."}'
                                """),
                new QuickstartStep(2, "Generate API Key",
                        "登录管理后台生成 API Key 对（apiKey + secret）。secret 仅展示一次，请妥善保存。",
                        """
                                curl -X POST https://api.nexuschain.io/api/v1/merchants/{id}/api-keys \\
                                  -H "Authorization: Bearer <admin-jwt>"
                                """),
                new QuickstartStep(3, "Create an Order",
                        "使用 API Key 创建支付订单。金额以最小单位传递（如 USDT 1.00 = 1000000）。",
                        """
                                curl -X POST https://api.nexuschain.io/api/v1/orders \\
                                  -H "X-NexusChain-ApiKey: YOUR_API_KEY" \\
                                  -H "Content-Type: application/json" \\
                                  -d '{"amount":1000000,"description":"Product purchase"}'
                                """),
                new QuickstartStep(4, "Initiate Payment",
                        "对订单发起支付，获取收银台 URL 引导用户完成付款。",
                        """
                                curl -X POST https://api.nexuschain.io/api/v1/orders/{id}/pay \\
                                  -H "X-NexusChain-ApiKey: YOUR_API_KEY" \\
                                  -H "Content-Type: application/json" \\
                                  -d '{"payerAddress":"0x..."}'
                                """),
                new QuickstartStep(5, "Handle Webhook",
                        "配置 webhook 接收端点，处理支付确认等链事件回调。验证 HMAC-SHA256 签名确保回调真实性。",
                        null)
        )));

        // Java 入门指南
        guides.put("java", new QuickstartGuide("java", List.of(
                new QuickstartStep(1, "Add SDK Dependency",
                        "在 build.gradle 中添加 NexusChain Java SDK 依赖。",
                        """
                                implementation 'org.nexus:nexuschain-sdk:1.2.0'
                                """),
                new QuickstartStep(2, "Initialize Client",
                        "使用 API Key 初始化 NexusClient。",
                        """
                                NexusClient client = NexusClient.builder()
                                        .apiKey("YOUR_API_KEY")
                                        .baseUrl("https://api.nexuschain.io")
                                        .build();
                                """),
                new QuickstartStep(3, "Create an Order",
                        "使用 SDK 创建支付订单。",
                        """
                                CreateOrderRequest request = new CreateOrderRequest()
                                        .setAmount(1000000)
                                        .setDescription("Product purchase");
                                PaymentOrder order = client.createOrder(request);
                                """),
                new QuickstartStep(4, "Initiate Payment",
                        "对订单发起支付。",
                        """
                                PaymentResult result = client.initiatePayment(order.getId(), "0x...");
                                """),
                new QuickstartStep(5, "Handle Webhook",
                        "配置 Spring Boot webhook 接收端点，验证签名后处理事件。",
                        """
                                @PostMapping("/webhooks/chain-events")
                                public ResponseEntity<String> handle(@RequestBody Map<String,Object> payload,
                                        @RequestHeader("X-NexusChain-Signature") String sig) {
                                    // 验证 HMAC-SHA256 签名
                                    // 处理 PAYMENT_CONFIRMED 事件
                                    return ResponseEntity.ok("OK");
                                }
                                """)
        )));

        // Python 入门指南
        guides.put("python", new QuickstartGuide("python", List.of(
                new QuickstartStep(1, "Install SDK",
                        "使用 pip 安装 NexusChain Python SDK。",
                        """
                                pip install nexuschain-sdk==1.1.3
                                """),
                new QuickstartStep(2, "Initialize Client",
                        "使用 API Key 初始化客户端。",
                        """
                                from nexuschain import NexusClient
                                client = NexusClient(api_key="YOUR_API_KEY", base_url="https://api.nexuschain.io")
                                """),
                new QuickstartStep(3, "Create an Order",
                        "使用 SDK 创建支付订单。",
                        """
                                order = client.create_order(amount=1000000, description="Product purchase")
                                """),
                new QuickstartStep(4, "Initiate Payment",
                        "对订单发起支付。",
                        """
                                result = client.initiate_payment(order.id, payer_address="0x...")
                                """),
                new QuickstartStep(5, "Handle Webhook",
                        "使用 Flask/FastAPI 配置 webhook 接收端点。",
                        """
                                @app.post("/webhooks/chain-events")
                                def handle():
                                    payload = request.json
                                    sig = request.headers.get("X-NexusChain-Signature")
                                    # 验证 HMAC-SHA256 签名
                                    return "OK"
                                """)
        )));

        // JavaScript 入门指南
        guides.put("javascript", new QuickstartGuide("javascript", List.of(
                new QuickstartStep(1, "Install SDK",
                        "使用 npm 安装 NexusChain JavaScript SDK。",
                        """
                                npm install @nexuschain/sdk@1.3.1
                                """),
                new QuickstartStep(2, "Initialize Client",
                        "使用 API Key 初始化客户端。",
                        """
                                import { NexusClient } from '@nexuschain/sdk';
                                const client = new NexusClient({ apiKey: 'YOUR_API_KEY', baseUrl: 'https://api.nexuschain.io' });
                                """),
                new QuickstartStep(3, "Create an Order",
                        "使用 SDK 创建支付订单。",
                        """
                                const order = await client.createOrder({ amount: 1000000, description: 'Product purchase' });
                                """),
                new QuickstartStep(4, "Initiate Payment",
                        "对订单发起支付。",
                        """
                                const result = await client.initiatePayment(order.id, '0x...');
                                """),
                new QuickstartStep(5, "Handle Webhook",
                        "使用 Express 配置 webhook 接收端点。",
                        """
                                app.post('/webhooks/chain-events', (req, res) => {
                                    const sig = req.headers['x-nexuschain-signature'];
                                    // 验证 HMAC-SHA256 签名
                                    res.send('OK');
                                });
                                """)
        )));

        // Go 入门指南
        guides.put("go", new QuickstartGuide("go", List.of(
                new QuickstartStep(1, "Install SDK",
                        "使用 go get 安装 NexusChain Go SDK。",
                        """
                                go get github.com/nexuschain/go-sdk@v0.9.0
                                """),
                new QuickstartStep(2, "Initialize Client",
                        "使用 API Key 初始化客户端。",
                        """
                                client := nexus.NewClient("YOUR_API_KEY", "https://api.nexuschain.io")
                                """),
                new QuickstartStep(3, "Create an Order",
                        "使用 SDK 创建支付订单。",
                        """
                                order, err := client.CreateOrder(&nexus.CreateOrderRequest{
                                    Amount:      1000000,
                                    Description: "Product purchase",
                                })
                                """),
                new QuickstartStep(4, "Initiate Payment",
                        "对订单发起支付。",
                        """
                                result, err := client.InitiatePayment(order.ID, "0x...")
                                """),
                new QuickstartStep(5, "Handle Webhook",
                        "使用 net/http 配置 webhook 接收端点。",
                        """
                                http.HandleFunc("/webhooks/chain-events", func(w http.ResponseWriter, r *http.Request) {
                                    // 验证 HMAC-SHA256 签名
                                    w.Write([]byte("OK"))
                                })
                                """)
        )));

        return Collections.unmodifiableMap(guides);
    }
}