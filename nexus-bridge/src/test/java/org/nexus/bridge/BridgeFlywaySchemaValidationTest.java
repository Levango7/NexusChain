package org.nexus.bridge;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 数据层收口守护测试（2026-10-27）。
 *
 * <p>验证两件事，缺一不可：</p>
 * <ol>
 *   <li>V0..V7 迁移能在 MySQL 兼容语法下真实执行（H2 用 MODE=MySQL）；</li>
 *   <li>执行后的 schema 能通过 Hibernate {@code ddl-auto=validate} 校验——
 *       即迁移脚本与 9 个实体的列名 / 类型 / 可空性一致。</li>
 * </ol>
 *
 * <p>为什么必须用 Spring 上下文：{@code validate} 是 Hibernate 启动阶段行为，
 * 只有真正创建 EntityManagerFactory 才会触发；单跑 Flyway 只能证明 SQL 能执行，
 * 证明不了与实体一致。</p>
 */
@SpringBootTest(properties = {
        // 覆盖 test profile：真实执行迁移并让 Hibernate 校验，而不是 create-drop
        "spring.datasource.url=jdbc:h2:mem:bridge_flyway;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.jpa.hibernate.ddl-auto=validate",
        // 桥的链上执行要求"真实上链或显式 mock"；本测试只验证 schema，开 mock 避免 fail-closed
        "nexus.bridge.mock-mode=true"
})
@ActiveProfiles("test")
class BridgeFlywaySchemaValidationTest {

    /**
     * 测试上下文未启用 tracing autoconfigure，BridgeServiceImpl 构造器需要 Tracer；
     * 与 BridgeFullChainIntegrationTest 保持同款 mock 方式。
     */
    @MockitoBean
    private Tracer tracer;

    @Test
    @DisplayName("V0..V7 迁移可执行，且 schema 通过 Hibernate validate")
    void migrationsApplyAndSchemaValidates() {
        // 上下文成功加载即代表：Flyway 执行完所有迁移 + Hibernate validate 通过。
    }
}
