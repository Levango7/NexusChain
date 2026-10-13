package org.nexus.gateway.subscription.claim;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订阅扣款幂等认领的<b>机制</b>回归：以真实 H2（MODE=MySQL）执行生产迁移
 * {@code V98__subscription_charge_claim.sql}，让唯一键约束真的落地，验证
 * {@link SubscriptionChargeClaimService#tryClaim} 的"同订阅同账期有且仅有一个认领"。
 *
 * <p>与 {@code SubscriptionChargeClaimGuardTest}（mock 认领服务，验接线）互补：
 * 这里验的是唯一键这道裁决本身在真实数据库上成立，以及它不误伤
 * "换账期 / 换订阅" 的再次认领（否则 dunning 重试会被永久挡住）。</p>
 *
 * <p>防线语义（同 {@code SettlementPersistenceSchemaValidateIT}）：以
 * {@code ddl-auto=validate} 让 Hibernate 校验 {@link SubscriptionChargeClaim}
 * 实体映射与 V98 DDL 逐列一致——任何"实体 ↔ 迁移 SQL 漂移"在此即刻失败，
 * 先于生产 validate 启动暴露。</p>
 */
@DataJpaTest
@ContextConfiguration(classes = {SubscriptionChargeClaimSchemaIT.SliceConfig.class})
@TestPropertySource(properties = {
        // 1) DataSource 初始化期执行生产 V98 DDL（先于 JPA validate）
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V98__subscription_charge_claim.sql",
        // 2) JPA 只校验不建表：表必须与实体逐列一致
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SubscriptionChargeClaimSchemaIT {

    /** 本防线只校验认领一个实体 ↔ V98 一表，故收窄扫描到认领包。 */
    @Configuration
    @EntityScan(basePackages = "org.nexus.gateway.subscription.claim")
    @EnableJpaRepositories(basePackages = "org.nexus.gateway.subscription.claim")
    @Import(SubscriptionChargeClaimService.class)
    static class SliceConfig {
    }

    @Autowired
    private SubscriptionChargeClaimService claimService;

    @Autowired
    private SubscriptionChargeClaimRepository repository;

    @Test
    void v98SchemaAndEntity_shouldBeAligned() {
        // 上下文成功启动 = V98 建表 + Hibernate validate 通过（实体↔DDL 逐列一致）+ 仓储装配成功。
        assertNotNull(claimService);
        assertNotNull(repository);
    }

    @Test
    void sameSubscriptionSameCycle_secondClaimIsRejected() {
        String cycleKey = LocalDateTime.now().minusMinutes(1).toString();

        assertTrue(claimService.tryClaim("SUB-IT-RACE", cycleKey), "首个认领应成功");
        assertFalse(claimService.tryClaim("SUB-IT-RACE", cycleKey),
                "同订阅同账期的第二个认领必须被唯一键拒绝（并发重复扣款的裁决点）");
        // 用按 (订阅, 账期) 收敛的计数：认领走独立事务即时提交，不随测试事务回滚，
        // 全局 count() 会被同类其他用例的已提交行污染。
        assertEquals(1, repository.countBySubscriptionIdAndCycleKey("SUB-IT-RACE", cycleKey),
                "被拒的认领不得落库");
    }

    @Test
    void newCycleOrOtherSubscription_isClaimableAgain() {
        String cycleKey = LocalDateTime.now().minusMinutes(1).toString();

        assertTrue(claimService.tryClaim("SUB-IT-RETRY", cycleKey));
        assertTrue(claimService.tryClaim("SUB-IT-RETRY", "2031-01-01T02:30:00"),
                "账期推进/dunning 重试后的新 key 必须可再次认领，否则失败重试会被永久挡住");
        assertTrue(claimService.tryClaim("SUB-IT-OTHER", cycleKey),
                "另一个订阅的同一 key 不受影响");
        assertEquals(1, repository.countBySubscriptionIdAndCycleKey("SUB-IT-RETRY", cycleKey));
        assertEquals(1, repository.countBySubscriptionIdAndCycleKey("SUB-IT-RETRY", "2031-01-01T02:30:00"));
        assertEquals(1, repository.countBySubscriptionIdAndCycleKey("SUB-IT-OTHER", cycleKey));
    }
}
