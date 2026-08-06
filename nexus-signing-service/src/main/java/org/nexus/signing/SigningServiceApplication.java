package org.nexus.signing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 签名服务独立启动入口。
 *
 * <p>Phase 1 微服务化：从 nexus-exchange-wallet 拆分出的签名服务独立 Spring Boot 应用，
 * 承载原 exchange-wallet 的 signing/ 子包全部端点 + NoncePool / NodeController 等签名必需组件。</p>
 *
 * <p>组件扫描范围：默认扫描 {@code org.nexus.signing} 包及其子包，覆盖：
 * <ul>
 *   <li>{@code controller}：TxController / WalletController / NodeController</li>
 *   <li>{@code keystore}：PlatformKeystore</li>
 *   <li>{@code mpc}：MPC 阈值签名全套（含 barrier / persistence / router / security / transport / wal 子包）</li>
 *   <li>{@code pool}：NoncePool / NonceState / PoolTask</li>
 *   <li>{@code storage}：Leveldb</li>
 *   <li>{@code util}：HttpRequestUtil / BeanToMapUtil</li>
 * </ul></p>
 *
 * <p>SCA 集成：
 * <ul>
 *   <li>Nacos discovery + config：bootstrap.yml 配置，自动注册到 Nacos</li>
 *   <li>Sentinel：application.yml 配置 transport-dashboard，熔断限流</li>
 *   <li>OpenFeign：@EnableFeignClients 启用，可调其他微服务</li>
 * </ul></p>
 */
@SpringBootApplication
@EnableFeignClients(basePackages = {"org.nexus.sdk.client.feign"})
@EnableScheduling
public class SigningServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SigningServiceApplication.class, args);
    }
}
