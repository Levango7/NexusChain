package org.nexus.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NexusChain Gateway - 商户支付网关启动类
 *
 * <p>组件扫描覆盖网关本体与中间服务层模块（nexus-settlement / nexus-compliance /
 * nexus-analytics / nexus-oracle）。这些模块以进程内库形式接入（composite build 依赖替换），
 * 其 @Service/@Component Bean（RiskEngine、规则链、KycService、AmlScreeningService、
 * PaymentEventCollector、PriceOracle 等）由网关容器统一装配。</p>
 *
 * <p>Phase 1 任务 #55：启用 OpenFeign 声明式调用，扫描 nexus-sdk 中的 Feign 客户端契约
 * （{@code org.nexus.sdk.client.feign} 包下 SigningServiceFeignClient /
 * WalletMgmtFeignClient / BridgeServiceFeignClient）。实际服务实例由 Nacos 服务发现
 * 解析，Sentinel 提供熔断降级（Phase 2 #61 补全 fallback 类）。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "org.nexus.gateway",
        "org.nexus.settlement",
        "org.nexus.compliance",
        "org.nexus.analytics",
        "org.nexus.oracle"
})
@EnableFeignClients(basePackages = "org.nexus.sdk.client.feign")
@EnableScheduling
@EnableAsync
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
