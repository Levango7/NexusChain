package org.nexus.gateway.health;

import org.nexus.gateway.config.GatewayConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Health indicator for NexusChain Core node connectivity.
 *
 * <p>性能优化（任务 #310）：注入共享的连接池化 RestTemplate。</p>
 */
@Component
public class ChainNodeHealthIndicator implements HealthIndicator {

    private final GatewayConfig config;
    private final RestTemplate restTemplate;

    @Autowired
    public ChainNodeHealthIndicator(GatewayConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
    }

    /** 测试用兼容构造器。 */
    public ChainNodeHealthIndicator(GatewayConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    @Override
    public Health health() {
        try {
            String url = config.getChain().getRpcUrl() + "/height";
            restTemplate.getForEntity(url, String.class);
            return Health.up().withDetail("rpcUrl", config.getChain().getRpcUrl()).build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("rpcUrl", config.getChain().getRpcUrl())
                    .withDetail("error", e.getMessage()).build();
        }
    }
}