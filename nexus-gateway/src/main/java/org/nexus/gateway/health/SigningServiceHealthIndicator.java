package org.nexus.gateway.health;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * signing-service 健康指标（gateway 主动探测下游服务）。
 *
 * <p>actuator/health 返回 signing-service 状态，
 * 影响 LoadBalancer 路由（不健康实例自动摘除）。</p>
 *
 * <p>探测策略（审计修复）：调用 {@link SigningServiceFeignClient#getCapability}
 * ——无副作用的只读探针端点。早期实现调用 {@code signTransfer} 生产端点：
 * 每 30 秒触发一次完整签名路径（读平台密钥库 + 写签名审计日志），且依赖下游
 * "恰好拒绝"才安全。现探针端点 {@code GET /api/v1/transfers/capability}
 * 只读、不读密钥库、不产生审计事件。</p>
 *
 * <p>判定：HTTP 可达且 statusCode=2000 → UP；连接异常 / 降级返回 null /
 * 非 2000 → DOWN。</p>
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
            Map<String, Object> resp = signingServiceClient.getCapability();
            Object code = resp == null ? null : resp.get("statusCode");
            boolean up = code instanceof Number && ((Number) code).intValue() == 2000;
            if (up) {
                return Health.up()
                        .withDetail("service", "nexus-signing-service")
                        .withDetail("probe", "capability")
                        .build();
            }
            return Health.down()
                    .withDetail("service", "nexus-signing-service")
                    .withDetail("probe", "capability")
                    .withDetail("statusCode", String.valueOf(code))
                    .build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("service", "nexus-signing-service")
                    .withDetail("error", e.getMessage()).build();
        }
    }
}
