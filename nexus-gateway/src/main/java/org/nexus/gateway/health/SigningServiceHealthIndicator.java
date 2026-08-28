package org.nexus.gateway.health;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * signing-service 健康指标（gateway 主动探测下游服务）。
 *
 * <p>actuator/health 返回 signing-service 状态，
 * 影响 LoadBalancer 路由（不健康实例自动摘除）。</p>
 *
 * <p>探测策略：调用 {@link SigningServiceFeignClient#signTransfer} 轻探
 * 签名服务可达性。传入探测参数（{@code __health_check__}），预期
 * signing-service 返回错误响应（fromPubkey 不匹配 platform keystore），
 * 但不抛出连接异常 → UP。若服务不可达，Feign 抛出连接异常 → DOWN。</p>
 *
 * <p>端点对齐修复（任务 #317）：原探测端点 {@code canSignViaMpc}
 * （{@code GET /api/v1/signing/capability}）已移除（TxController 中不存在），
 * 改用 {@code signTransfer} 探测。</p>
 *
 * <p>设计文档 §4.6.3 服务健康检查增强。</p>
 */
@Component("signingServiceHealth")
public class SigningServiceHealthIndicator implements HealthIndicator {

    private static final String PROBE_FROM_PUBKEY = "__health_check__";
    private static final String PROBE_TO_PUBKEY_HASH = "__health_check__";

    private final SigningServiceFeignClient signingServiceClient;

    public SigningServiceHealthIndicator(SigningServiceFeignClient signingServiceClient) {
        this.signingServiceClient = signingServiceClient;
    }

    @Override
    public Health health() {
        try {
            // 用 signTransfer 轻量探测（传入探测参数，预期返回错误响应但不抛出连接异常）
            signingServiceClient.signTransfer(PROBE_FROM_PUBKEY, PROBE_TO_PUBKEY_HASH, BigDecimal.ZERO);
            return Health.up().withDetail("service", "nexus-signing-service").build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("service", "nexus-signing-service")
                    .withDetail("error", e.getMessage()).build();
        }
    }
}
