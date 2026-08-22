package org.nexus.gateway.health;

import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * wallet-service 健康指标（gateway 主动探测下游服务）。
 *
 * <p>actuator/health 返回 wallet-service 状态，
 * 影响 LoadBalancer 路由（不健康实例自动摘除）。</p>
 *
 * <p>探测策略：调用 {@link WalletMgmtFeignClient#verifyAddress} 轻探
 * 钱包管理服务可达性。该方法为轻量只读端点（{@code GET /api/v1/wallet/verifyAddress}），
 * 无状态、不涉及私钥，适合作为健康检查探针。</p>
 *
 * <p>设计文档 §4.6.3 服务健康检查增强。</p>
 */
@Component("walletServiceHealth")
public class WalletServiceHealthIndicator implements HealthIndicator {

    private static final String PROBE_ADDRESS = "NEX0000000000000000000000000000000000";

    private final WalletMgmtFeignClient walletMgmtClient;

    public WalletServiceHealthIndicator(WalletMgmtFeignClient walletMgmtClient) {
        this.walletMgmtClient = walletMgmtClient;
    }

    @Override
    public Health health() {
        try {
            // 用 verifyAddress 轻量探测（无状态、不涉及私钥）
            walletMgmtClient.verifyAddress(PROBE_ADDRESS);
            return Health.up().withDetail("service", "nexus-wallet-service").build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("service", "nexus-wallet-service")
                    .withDetail("error", e.getMessage()).build();
        }
    }
}
