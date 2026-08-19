package org.nexus.gateway.apiversion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.controller.v2.MerchantV2Controller;
import org.nexus.gateway.controller.v2.OrderV2Controller;
import org.nexus.gateway.controller.v2.PaymentV2Controller;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RPC文档一致性测试：openapi-v2.yaml vs v2 Controller 实际端点。
 *
 * <p>用反射动态扫描 3 个 v2 Controller（OrderV2Controller / PaymentV2Controller /
 * MerchantV2Controller）的 @*Mapping 注解，提取所有 HTTP 端点，
 * 与 openapi-v2.yaml 中定义的端点列表比对，确保文档与代码一致。
 *
 * <p>三向断言：
 * <ul>
 *   <li>代码端点全部在文档中定义（无未文档化端点）</li>
 *   <li>文档端点全部在代码中实现（无未实现端点）</li>
 *   <li>端点总数一致</li>
 * </ul>
 *
 * <p>DOCUMENTED_ENDPOINTS 列表来自 docs/openapi-v2.yaml 的 paths 段。
 * 文档变更时需同步更新此列表。
 *
 * @since 2.10.0
 */
@DisplayName("RPC文档一致性：openapi-v2.yaml vs v2 Controller")
class OpenApiV2ConsistencyTest {

    /**
     * openapi-v2.yaml 中定义的端点（12个）。
     * 格式：METHOD /path
     * 文档变更时需同步更新此列表。
     */
    private static final Set<String> DOCUMENTED_ENDPOINTS = Set.of(
            // Order v2
            "GET /api/v2/orders",
            "POST /api/v2/orders",
            "GET /api/v2/orders/{id}",
            "GET /api/v2/orders/{id}/finality",
            "POST /api/v2/orders/{id}/pay",
            "POST /api/v2/orders/{id}/refund",
            // Payment v2
            "POST /api/v2/payments/batch",
            // Merchant v2
            "POST /api/v2/merchants/register",
            "GET /api/v2/merchants/{id}",
            "POST /api/v2/merchants/{id}/verify",
            "POST /api/v2/merchants/{id}/api-keys",
            "DELETE /api/v2/merchants/{id}/api-keys"
    );

    /** v2 Controller 类列表 */
    private static final Class<?>[] V2_CONTROLLERS = {
            OrderV2Controller.class,
            PaymentV2Controller.class,
            MerchantV2Controller.class
    };

    // ==================== 一致性测试 ====================

    @Test
    @DisplayName("1. 代码端点全部在文档中定义（无未文档化端点）")
    void allControllerEndpointsAreDocumented() {
        Set<String> actual = scanAllControllerEndpoints();

        Set<String> undocumented = new TreeSet<>(actual);
        undocumented.removeAll(DOCUMENTED_ENDPOINTS);
        assertTrue(undocumented.isEmpty(),
                "以下端点在Controller中存在但openapi-v2.yaml未定义: " + undocumented
                        + "\n请将缺失端点补充到 docs/openapi-v2.yaml");
    }

    @Test
    @DisplayName("2. 文档端点全部在代码中实现（无未实现端点）")
    void allDocumentedEndpointsExistInCode() {
        Set<String> actual = scanAllControllerEndpoints();

        Set<String> missing = new TreeSet<>(DOCUMENTED_ENDPOINTS);
        missing.removeAll(actual);
        assertTrue(missing.isEmpty(),
                "以下端点在openapi-v2.yaml中定义但Controller未实现: " + missing
                        + "\n请实现缺失端点或从文档中移除");
    }

    @Test
    @DisplayName("3. 端点总数一致")
    void endpointCountMatches() {
        Set<String> actual = scanAllControllerEndpoints();

        assertEquals(DOCUMENTED_ENDPOINTS.size(), actual.size(),
                "文档端点数(" + DOCUMENTED_ENDPOINTS.size() + ")与代码端点数(" + actual.size() + ")不一致"
                        + "\n文档: " + new TreeSet<>(DOCUMENTED_ENDPOINTS)
                        + "\n代码: " + actual);
    }

    // ==================== 反射扫描工具 ====================

    /** 扫描所有 v2 Controller 的端点 */
    private Set<String> scanAllControllerEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        for (Class<?> controller : V2_CONTROLLERS) {
            endpoints.addAll(extractEndpoints(controller));
        }
        return endpoints;
    }

    /** 从单个 Controller 类提取所有端点 */
    private Set<String> extractEndpoints(Class<?> controllerClass) {
        Set<String> endpoints = new TreeSet<>();
        String prefix = extractClassPrefix(controllerClass);

        for (Method method : controllerClass.getDeclaredMethods()) {
            String httpMethod = extractHttpMethod(method);
            if (httpMethod == null) continue;

            String[] paths = extractMethodPaths(method);
            for (String path : paths) {
                String full = prefix + path;
                endpoints.add(httpMethod + " " + full);
            }
        }
        return endpoints;
    }

    /** 提取类级 @RequestMapping 前缀 */
    private String extractClassPrefix(Class<?> cls) {
        RequestMapping rm = cls.getAnnotation(RequestMapping.class);
        if (rm == null) return "";
        String[] values = rm.value();
        return values.length > 0 ? values[0] : "";
    }

    /** 提取方法级 @*Mapping 的路径 */
    private String[] extractMethodPaths(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) return get.value().length > 0 ? get.value() : new String[]{""};

        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) return post.value().length > 0 ? post.value() : new String[]{""};

        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) return put.value().length > 0 ? put.value() : new String[]{""};

        DeleteMapping del = method.getAnnotation(DeleteMapping.class);
        if (del != null) return del.value().length > 0 ? del.value() : new String[]{""};

        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) return patch.value().length > 0 ? patch.value() : new String[]{""};

        RequestMapping rm = method.getAnnotation(RequestMapping.class);
        if (rm != null) return rm.value().length > 0 ? rm.value() : new String[]{""};

        return new String[0];
    }

    /** 提取 HTTP 方法 */
    private String extractHttpMethod(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) return "GET";
        if (method.isAnnotationPresent(PostMapping.class)) return "POST";
        if (method.isAnnotationPresent(PutMapping.class)) return "PUT";
        if (method.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        if (method.isAnnotationPresent(PatchMapping.class)) return "PATCH";

        RequestMapping rm = method.getAnnotation(RequestMapping.class);
        if (rm != null && rm.method().length > 0) {
            return rm.method()[0].name();
        }
        return null;
    }
}