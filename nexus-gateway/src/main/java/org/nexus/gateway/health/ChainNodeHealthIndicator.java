package org.nexus.gateway.health;

import org.nexus.gateway.config.GatewayConfig;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Health indicator for NexusChain Core node connectivity.
 */
@Component
public class ChainNodeHealthIndicator implements HealthIndicator {

    private final GatewayConfig config;
    private final RestTemplate restTemplate = new RestTemplate();

    public ChainNodeHealthIndicator(GatewayConfig config) {
        this.config = config;
    }

    @Override
    public Health health() {
        try {
            String url = config.getChain().getRpcUrl() + "/height";
            restTemplate.getForEntity(url, String.class);
            return Health.up().withDetail("rpcUrl", config.getChain().getRpcUrl()).build();
        } catch (Exception e) {
            return Health.down().withDetail("rpcUrl", config.getChain().getRpcUrl())
                    .withDetail("error", e.getMessage()).build();
        }
    }
}