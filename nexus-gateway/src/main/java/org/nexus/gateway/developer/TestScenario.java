package org.nexus.gateway.developer;

import java.util.Map;

/**
 * 测试场景 DTO — 描述预置的交互式测试场景。
 */
public class TestScenario {

    /** 场景名称 */
    private String name;

    /** 场景描述 */
    private String description;

    /** 目标端点路径 */
    private String endpoint;

    /** HTTP 方法 */
    private String method;

    /** 测试参数 */
    private Map<String, Object> params;

    /** 预期响应 */
    private Map<String, Object> expectedResponse;

    public TestScenario() {
    }

    public TestScenario(String name, String description, String endpoint, String method,
                        Map<String, Object> params, Map<String, Object> expectedResponse) {
        this.name = name;
        this.description = description;
        this.endpoint = endpoint;
        this.method = method;
        this.params = params;
        this.expectedResponse = expectedResponse;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }

    public Map<String, Object> getExpectedResponse() { return expectedResponse; }
    public void setExpectedResponse(Map<String, Object> expectedResponse) { this.expectedResponse = expectedResponse; }
}