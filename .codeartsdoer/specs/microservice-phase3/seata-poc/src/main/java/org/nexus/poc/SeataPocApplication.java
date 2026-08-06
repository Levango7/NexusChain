package org.nexus.poc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * Seata 版本兼容 POC 启动类。
 *
 * <p>仅验证依赖解析 + 编译 + SpringBoot 自动配置加载，不真正启动 Seata Server / Nacos。
 * 若本类能编译通过且 bootJar 能打包，则证明 seata-spring-boot-starter 2.0.0 与
 * SpringBoot 3.2.5 + SCA 2023.0.1.0 兼容（风险 R1 闭环）。</p>
 *
 * <p>设计文档 §4.1 版本对齐矩阵 / 风险 R1。</p>
 */
@SpringBootApplication
@EnableFeignClients
public class SeataPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeataPocApplication.class, args);
    }
}