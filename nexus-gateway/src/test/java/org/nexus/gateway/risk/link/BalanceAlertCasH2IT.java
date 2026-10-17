package org.nexus.gateway.risk.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.account.AccountStatus;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * alertFlag CAS 的<b>机制</b>回归（#35）：在真实 H2 上让
 * {@link MerchantAccountRepository#compareAndSetAlertFlag} 的谓词真的执行一次。
 *
 * <p>与 {@code BalanceAlertCasTest}（mock，验接线）互补：本类验的是 SQL 本身——
 * 「有且仅有一个调用方返回 1」由数据库裁决，而 {@code = NULL} 在 SQL 三值逻辑下恒不成立，
 * 正是 {@code compareAndSetAlertFlagFromNull} 必须单独存在的理由。这两点都不能靠 mock 证明。</p>
 *
 * <p><b>为什么这里不跑生产 DDL（与 {@code SubscriptionChargeClaimSchemaIT} 的差别）：</b>
 * {@code V44__add_merchant_accounts.sql} 用的是 MySQL 专有写法（建表内联
 * {@code INDEX idx_...} / {@code ENGINE=InnoDB} / {@code COMMENT}），H2 即使在
 * {@code MODE=MySQL} 下也解析不了——实测报
 * {@code Unknown data type: "IDX_MA_MERCHANT_ID_TYPE"}。gateway 有 28 份迁移脚本属同一类，
 * 所以本仓的分工是：<b>迁移脚本能否真的执行，由 CI 的「Flyway 迁移预检」在 MySQL 8 服务容器上验</b>
 * （ci.yml 的 {@code flyway-migration-check}）；单元测试用的 H2 库按
 * {@code application-test.yml:29-44} 的既有口径由实体生成（{@code ddl-auto=create-drop}，
 * Flyway 在测试库禁用）。因此本类守的是 <b>JPQL 谓词的 CAS 语义</b>，
 * <b>不</b>声称守实体 ↔ 生产 DDL 的列名/类型对齐——那条不在本用例的能力范围内。</p>
 */
@DataJpaTest
@ContextConfiguration(classes = BalanceAlertCasH2IT.SliceConfig.class)
class BalanceAlertCasH2IT {

    /** 本防线只覆盖账户切片（两个实体 + merchant_accounts 仓储）。 */
    @Configuration
    @EntityScan(basePackages = "org.nexus.gateway.account")
    @EnableJpaRepositories(basePackages = "org.nexus.gateway.account")
    static class SliceConfig {
    }

    @Autowired
    private MerchantAccountRepository accountRepository;

    @Test
    @DisplayName("NULL 起点：IS NULL 分支翻动成功，重复调用返回 0（去重的裁决点）")
    void fromNullTransitionHappensExactlyOnce() {
        MerchantAccount account = persist(1L, null);
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 17, 1, 0, 0);

        assertEquals(1, accountRepository.compareAndSetAlertFlagFromNull(account.getId(), "WARNING", t1),
                "第一个调用方应完成跃迁");
        assertEquals(0, accountRepository.compareAndSetAlertFlagFromNull(account.getId(), "CRITICAL", t1),
                "旧值已不是 NULL，第二个调用方必须拿不到");

        MerchantAccount reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertEquals("WARNING", reloaded.getAlertFlag());
        assertEquals(t1, reloaded.getUpdatedAt(), "bulk update 不触发实体监听器，updated_at 由语句自己推");
        assertEquals(1L, reloaded.getVersion().longValue(),
                "CAS 必须自己推 version，否则同账户的实体级写入方会误以为乐观锁还成立");
    }

    @Test
    @DisplayName("旧值相等才翻：带过期旧值的一方返回 0")
    void equalityPredicateRejectsStaleExpectation() {
        MerchantAccount account = persist(2L, "WARNING");

        assertEquals(0, accountRepository.compareAndSetAlertFlag(
                account.getId(), "CRITICAL", "EMERGENCY", LocalDateTime.now()),
                "期望旧值 CRITICAL 与库里的 WARNING 不符 ⇒ 不许翻");
        assertEquals(1, accountRepository.compareAndSetAlertFlag(
                account.getId(), "WARNING", "CRITICAL", LocalDateTime.now()),
                "期望旧值命中 ⇒ 唯一一方翻动成功");
        assertEquals(0, accountRepository.compareAndSetAlertFlag(
                account.getId(), "WARNING", "EMERGENCY", LocalDateTime.now()),
                "已被翻走，第二个副本拿不到");

        assertEquals("CRITICAL", accountRepository.findById(account.getId()).orElseThrow().getAlertFlag());
    }

    @Test
    @DisplayName("把 = :oldFlag 直接用于 NULL 旧值必然匹配不到 ⇒ 证明 FromNull 分支不是多余的")
    void equalityPredicateNeverMatchesNullOldFlag() {
        MerchantAccount account = persist(3L, null);

        assertEquals(0, accountRepository.compareAndSetAlertFlag(
                account.getId(), null, "WARNING", LocalDateTime.now()),
                "SQL 三值逻辑：alert_flag = NULL 恒不成立，不能靠等值分支处理 NULL 起点");
        assertNull(accountRepository.findById(account.getId()).orElseThrow().getAlertFlag());
    }

    @Test
    @DisplayName("跃迁到 null（预警解除）也要原子发生，且解除后不留旧值")
    void transitionBackToNullIsAlsoCas() {
        MerchantAccount account = persist(4L, "CRITICAL");
        LocalDateTime t = LocalDateTime.of(2026, 10, 17, 2, 0, 0);

        assertEquals(1, accountRepository.compareAndSetAlertFlag(account.getId(), "CRITICAL", null, t));
        assertEquals(0, accountRepository.compareAndSetAlertFlag(account.getId(), "CRITICAL", "WARNING", t),
                "已解除的行不再满足旧值谓词");

        MerchantAccount reloaded = accountRepository.findById(account.getId()).orElseThrow();
        assertNull(reloaded.getAlertFlag());
        assertEquals(t, reloaded.getUpdatedAt());
    }

    // ==================== 辅助 ====================

    private MerchantAccount persist(long seq, String alertFlag) {
        MerchantAccount account = new MerchantAccount();
        account.setAccountId("MA-IT-" + seq);
        account.setMerchantId(88000L + seq);
        account.setAccountType(AccountType.BALANCE);
        account.setBalance(new BigDecimal("100"));
        account.setStatus(AccountStatus.ACTIVE);
        account.setAlertFlag(alertFlag);
        MerchantAccount saved = accountRepository.saveAndFlush(account);
        // @Version 起点：新建实体由 Hibernate 置 0，断言里的目标值以此为基准
        assertEquals(0L, saved.getVersion(), "前置条件：新行 version 应为 0");
        return saved;
    }
}
