package org.nexus.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NexusChain Gateway - 商户支付网关启动类
 *
 * <p>组件扫描覆盖网关本体与中间服务层模块（nexus-settlement / nexus-compliance /
 * nexus-analytics / nexus-oracle）。这些模块以进程内库形式接入（composite build 依赖替换），
 * 其 @Service/@Component Bean（RiskEngine、规则链、KycService、AmlScreeningService、
 * PaymentEventCollector、PriceOracle 等）由网关容器统一装配。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "org.nexus.gateway",
        "org.nexus.settlement",
        "org.nexus.compliance",
        "org.nexus.analytics",
        "org.nexus.oracle"
})
@EnableScheduling
@EnableAsync
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
