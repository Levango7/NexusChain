package org.nexus.gateway.developer;

import java.util.List;
import java.util.Map;

/**
 * API 端点元数据 DTO — 描述单个 API 端点的完整文档信息。
 *
 * <p>用于开发者门户的 API 目录展示，包含路径、方法、描述、版本、
 * 认证要求、参数定义、响应格式和错误码。</p>
 */
public class ApiEndpointMeta {

    /** 端点路径，如 /api/v1/orders */
    private String path;

    /** HTTP 方法：GET, POST, PUT, DELETE */
    private String method;

    /** 简要描述（一句话摘要） */
    private String summary;

    /** 详细描述 */
    private String description;

    /** API 版本：v1, v2 */
    private String version;

    /** 是否需要认证 */
    private boolean authRequired;

    /** 参数列表（参数名 → 参数描述） */
    private List<Map<String, Object>> parameters;

    /** 响应格式描述（状态码 → 响应体结构） */
    private Map<String, Object> responses;

    /** 错误码列表（错误码 → 错误描述） */
    private Map<String, String> errorCodes;

    public ApiEndpointMeta() {
    }

    public ApiEndpointMeta(String path, String method, String summary, String description,
                           String version, boolean authRequired,
                           List<Map<String, Object>> parameters,
                           Map<String, Object> responses,
                           Map<String, String> errorCodes) {
        this.path = path;
        this.method = method;
        this.summary = summary;
        this.description = description;
        this.version = version;
        this.authRequired = authRequired;
        this.parameters = parameters;
        this.responses = responses;
        this.errorCodes = errorCodes;
    }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public boolean isAuthRequired() { return authRequired; }
    public void setAuthRequired(boolean authRequired) { this.authRequired = authRequired; }

    public List<Map<String, Object>> getParameters() { return parameters; }
    public void setParameters(List<Map<String, Object>> parameters) { this.parameters = parameters; }

    public Map<String, Object> getResponses() { return responses; }
    public void setResponses(Map<String, Object> responses) { this.responses = responses; }

    public Map<String, String> getErrorCodes() { return errorCodes; }
    public void setErrorCodes(Map<String, String> errorCodes) { this.errorCodes = errorCodes; }
}