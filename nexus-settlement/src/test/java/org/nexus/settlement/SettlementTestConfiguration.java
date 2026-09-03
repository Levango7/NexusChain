package org.nexus.settlement;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * nexus-settlement 测试切片基座配置。
 *
 * <p>nexus-settlement 是供 gateway 消费的库模块（composite build），不产出
 * Spring Boot 应用，main 源码集没有 {@code @SpringBootApplication} 启动类。
 * 而 {@code @JdbcTest}/{@code @DataJpaTest} 等切片测试启动时需从测试类所在包
 * 向上搜索 {@code @SpringBootConfiguration}，缺失即抛
 * {@code Unable to find a @SpringBootConfiguration}。</p>
 *
 * <p>{@code @DataJpaTest} 还需 {@code @EnableAutoConfiguration} 检索自动配置包
 * （{@code Unable to retrieve @EnableAutoConfiguration base packages}），故叠加。
 * 本类仅存在于 test 源码集，为集成测试提供切片搜索锚点；
 * 不开启组件扫描，保持切片语义纯净。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class SettlementTestConfiguration {
}