package org.nexus.gateway.resilience;

import java.util.List;

/**
 * 降级路由配置 DTO — 用于 REST API 返回和更新降级路由配置。
 */
public class FallbackConfigDTO {

    private String connector;
    private List<String> fallbacks;

    public FallbackConfigDTO() {
    }

    public FallbackConfigDTO(String connector, List<String> fallbacks) {
        this.connector = connector;
        this.fallbacks = fallbacks;
    }

    public String getConnector() {
        return connector;
    }

    public void setConnector(String connector) {
        this.connector = connector;
    }

    public List<String> getFallbacks() {
        return fallbacks;
    }

    public void setFallbacks(List<String> fallbacks) {
        this.fallbacks = fallbacks;
    }
}