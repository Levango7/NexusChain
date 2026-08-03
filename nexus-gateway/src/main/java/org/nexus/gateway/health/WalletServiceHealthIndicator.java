package org.nexus.gateway.health;

import org.nexus.gateway.config.GatewayConfig;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Health indicator for exchange-wallet service connectivity.
 */
@Component
public class WalletServiceHealthIndicator implements HealthIndicator {

    private final GatewayConfig config;
    private final RestTemplate restTemplate = new RestTemplate();

    public WalletServiceHealthIndicator(GatewayConfig config) {
        this.config = config;
    }

    @Override
    public Health health() {
        try {
            String url = config.getExchangeWallet().getBaseUrl() + "/verifyAddress?address=test";
            restTemplate.getForEntity(url, String.class);
            return Health.up().withDetail("baseUrl", config.getExchangeWallet().getBaseUrl()).build();
        } catch (Exception e) {
            return Health.down().withDetail("baseUrl", config.getExchangeWallet().getBaseUrl())
                    .withDetail("error", e.getMessage()).build();
        }
    }
}