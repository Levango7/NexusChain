package org.nexus.gateway.health;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * signing-service 健康指标（gateway 主动探测下游服务）。
 *
 * <p>actuator/health 返回 signing-service 状态，
 * 影响 LoadBalancer 路由（不健康实例自动摘除）。</p>
 *
 * <p>探测策略：调用 {@link SigningServiceFeignClient#canSignViaMpc} 轻探
 * 签名服务可达性。该方法为轻量只读端点（{@code GET /api/v1/signing/capability}），
 * 不触发实际签名 + 广播，适合作为健康检查探针。</p>
 *
 * <p>设计文档 §4.6.3 服务健康检查增强。</p>
 */
@Component("signingServiceHealth")
public class SigningServiceHealthIndicator implements HealthIndicator {

    private final SigningServiceFeignClient signingServiceClient;

    public SigningServiceHealthIndicator(SigningServiceFeignClient signingServiceClient) {
        this.signingServiceClient = signingServiceClient;
    }

    @Override
    public Health health() {
        try {
            // 用 canSignViaMpc 轻量探测（不触发签名）
            signingServiceClient.canSignViaMpc(BigDecimal.ONE);
            return Health.up().withDetail("service", "nexus-signing-service").build();
        } catch (Exception e) {
            return Health.down().withDetail("service", "nexus-signing-service")
                    .withDetail("error", e.getMessage()).build();
        }
    }
}