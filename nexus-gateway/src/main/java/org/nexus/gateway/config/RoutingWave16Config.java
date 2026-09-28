package org.nexus.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wave 16 路由增强配置注册。
 *
 * <p>启用 {@link RoutingWave16Properties}（prefix = {@code nexus.routing}）的
 * 配置属性绑定，供各 Wave 16 服务构造器注入。遵循 StructuredLogConfig 的
 * 注册模式（本工程未开启 @ConfigurationPropertiesScan）。</p>
 */
@Configuration
@EnableConfigurationProperties(RoutingWave16Properties.class)
public class RoutingWave16Config {
}
