package org.nexus.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NexusChain Gateway - 商户支付网关启动类
 *
 * <p>组件扫描覆盖网关本体与两个中间服务层模块（nexus-settlement / nexus-compliance）。
 * 这两个模块以进程内库形式接入（composite build 依赖替换），其 @Service/@Component
 * Bean（RiskEngine、规则链、KycService、AmlScreeningService 等）由网关容器统一装配，
 * 网关侧的风控/合规桩实现通过依赖注入委托给它们。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "org.nexus.gateway",
        "org.nexus.settlement",
        "org.nexus.compliance"
})
@EnableScheduling
@EnableAsync
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
