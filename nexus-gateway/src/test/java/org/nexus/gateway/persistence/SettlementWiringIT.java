package org.nexus.gateway.persistence;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.SettlementPersistenceConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 清结算账务核心持久化接线测试（gateway 组合根装配验证）。
 *
 * <p>验证生产接线 {@link SettlementPersistenceConfig} 在 gateway 测试上下文中
 * 能装配 composite build 库（nexus-settlement）的 JPA 实体与仓储：
 * {@code @EntityScan}（org.nexus.gateway + settlement 两个包）与
 * {@code @EnableJpaRepositories}（gateway 既有仓库包 + settlement 两个包）。</p>
 *
 * <p>使用 test profile 默认的 {@code ddl-auto=create-drop}（Hibernate 自建全部实体表，
 * 不执行迁移脚本——gateway V1..V17 的 MySQL 方言脚本在 H2 上有兼容性限制，
 * 由 SchemaValidateIT 单独守 V18 ↔ 实体对齐防线）。</p>
 *
 * <p>本测试通过 = 生产接线对 composite 实体的扫描/装配正确，
 * gateway 与 settlement 实体可共存于同一 EntityManagerFactory。</p>
 */
@DataJpaTest
@ContextConfiguration(classes = {SettlementPersistenceConfig.class})
// L3 预演（缺陷 #20 连带，2026-10-13）：Boot 4 的 spring-boot-starter-flyway 补齐后
// @DataJpaTest slice 也会加载 Flyway 自动配置，全量 MySQL 迁移在 H2 上解析失败；
// 本测试按类注释口径只用 Hibernate create-drop 建表，显式关闭 Flyway。
@TestPropertySource(properties = "spring.flyway.enabled=false")
class SettlementWiringIT {

    /** 接线验证 1：settlement ClearingOrderRepository 经生产 config 装配 */
    @Autowired
    private org.nexus.settlement.clearing.ClearingOrderRepository clearingOrderRepository;

    /** 接线验证 2：settlement SettlementRecordRepository 经生产 config 装配 */
    @Autowired
    private org.nexus.settlement.reconciliation.SettlementRecordRepository settlementRecordRepository;

    @Test
    void compositeSettlementRepositories_shouldBeWiredByGatewayConfig() {
        assertNotNull(clearingOrderRepository);
        assertNotNull(settlementRecordRepository);
    }
}