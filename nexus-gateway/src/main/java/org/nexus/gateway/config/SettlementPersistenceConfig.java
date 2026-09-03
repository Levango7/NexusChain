package org.nexus.gateway.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 清结算账务核心持久化接线（Composition Root 持久化切面）。
 *
 * <p>nexus-settlement 经根 settings.gradle 的 composite build（includeBuild）
 * 接入，其编译产物以普通 jar 出现在 gateway 运行时 classpath（同 JVM）。
 * 但 {@code GatewayApplication} 默认只扫描 {@code org.nexus.gateway} 包的
 * {@code @Entity} 与 JpaRepository（Spring Data 默认以启动类包为根），
 * 扫不到 {@code org.nexus.settlement} 的实体与仓库。</p>
 *
 * <p>本 config 显式扩展扫描范围：
 * <ul>
 *   <li>{@link EntityScan}：{@code org.nexus.gateway} 全包（既有实体——
 *       显式声明会替代默认扫描，必须覆盖 gateway 全部实体包）+
 *       settlement 的 {@code clearing}（ClearingOrder）与
 *       {@code reconciliation}（SettlementRecord）</li>
 *   <li>{@link EnableJpaRepositories}：{@code org.nexus.gateway} 全包
 *       （repository/orchestration/clearing/tenant/subscription/risk/refund 等
 *       15+ 既有仓储）+ settlement 两个仓库包</li>
 * </ul>
 * 使 gateway 容器装配 settlement 的 JPA 实体/仓储，
 * 供 SettlementEventCollector / InMemoryChainRecordSource /
 * InMemoryBankRecordSource / DefaultClearingEngine 的可选注入消费。</p>
 *
 * <p>独立 {@code @Configuration} 而非注解在启动类上：避免启动类与持久化切面耦合，
 * 且便于集成测试以 {@code @ContextConfiguration(classes = ...)} 复用。</p>
 */
@Configuration
@EnableJpaRepositories(basePackages = {
        "org.nexus.gateway",
        "org.nexus.settlement.clearing",
        "org.nexus.settlement.reconciliation"
})
@EntityScan(basePackages = {
        "org.nexus.gateway",
        "org.nexus.settlement.clearing",
        "org.nexus.settlement.reconciliation"
})
public class SettlementPersistenceConfig {
}