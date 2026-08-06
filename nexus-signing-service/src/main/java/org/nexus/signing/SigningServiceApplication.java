package org.nexus.signing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 签名服务独立启动入口（骨架）。
 *
 * <p>PoC 阶段：仅用于验证模块可独立编译与装配。完整迁移后本类将作为
 * 签名服务独立部署的 Spring Boot 启动类，承载原 exchange-wallet 的
 * signing/ 子包全部端点。</p>
 *
 * <p>当前不通过 {@code springBoot.mainClass} 启用 fat jar 打包，
 * 模块以普通 jar 形式供 gateway composite build 消费。</p>
 */
@SpringBootApplication
public class SigningServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SigningServiceApplication.class, args);
    }
}