package org.nexus.gateway.health;

import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * wallet-service 健康指标（gateway 主动探测下游服务）。
 *
 * <p>actuator/health 返回 wallet-service 状态，
 * 影响 LoadBalancer 路由（不健康实例自动摘除）。</p>
 *
 * <p>探测策略：调用 {@link WalletMgmtFeignClient#isAddressWhitelisted} 轻探
 * 钱包管理服务可达性。该方法为轻量只读 GET 端点
 * （{@code GET /api/v1/wallet/whitelist/check}），适合作为健康检查探针。</p>
 *
 * <p>端点对齐修复（任务 #317）：原探测端点 {@code verifyAddress}
 * （{@code GET /api/v1/wallet/verifyAddress}）已移除（WalletController 中不存在），
 * 改用 {@code isAddressWhitelisted} 探测。</p>
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
            // 用 isAddressWhitelisted 轻量探测（GET 只读端点，轻量级）
            walletMgmtClient.isAddressWhitelisted(PROBE_ADDRESS);
            return Health.up().withDetail("service", "nexus-wallet-service").build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("service", "nexus-wallet-service")
                    .withDetail("error", e.getMessage()).build();
        }
    }
}
