package org.nexus.consensus.finality.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ValidatorSetPersistence} 重放水位（nonce）测试 —— PLAN-001 签名扩展【切片 2】。
 *
 * <p>覆盖：nonce 列的增量 DDL 在 H2(PostgreSQL 兼容模式) 可建、upsert 的
 * "只增不减"语义（乱序/老调用方不得回退水位）、未知地址返回 0、
 * 水位跨"新实例重放"仍可读（重启后重放护栏的水位来源）。</p>
 */
class ValidatorSetPersistenceNonceTest {

    private JdbcTemplate jdbc;
    private ValidatorRegistry registry;
    private StakingServiceImpl staking;
    private ValidatorSetPersistence persistence;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:vsetnonce_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking(staking, registry);
        persistence = new ValidatorSetPersistence(jdbc, registry, staking);
        persistence.init();
    }

    private void injectRegistryIntoStaking(StakingServiceImpl target, ValidatorRegistry targetRegistry) {
        try {
            var field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(target, targetRegistry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void nonceColumnAddedAndReadable() {
        persistence.upsert("addr-A", "pub-A", new BigDecimal("1000"), 100L);
        assertEquals(100L, persistence.lastNonce("addr-A"));
        assertEquals(0L, persistence.lastNonce("addr-unknown"), "未知地址返回 0");
    }

    @Test
    void nonceIsMonotonicEvenForOlderOrLegacyWriters() {
        persistence.upsert("addr-A", "pub-A", new BigDecimal("1000"), 200L);
        assertEquals(200L, persistence.lastNonce("addr-A"));

        // 乱序到达的旧 nonce：不得回退水位
        persistence.upsert("addr-A", "pub-B", new BigDecimal("1000"), 50L);
        assertEquals(200L, persistence.lastNonce("addr-A"), "旧 nonce 不得回退水位");

        // 老调用方（3 参重载 = nonce 0）：同样不得回退
        persistence.upsert("addr-A", "pub-C", new BigDecimal("1000"));
        assertEquals(200L, persistence.lastNonce("addr-A"), "legacy 3-arg upsert 不得回退水位");

        // 更新的 nonce：推进
        persistence.upsert("addr-A", "pub-D", new BigDecimal("1000"), 300L);
        assertEquals(300L, persistence.lastNonce("addr-A"));
    }

    @Test
    void nonceSurvivesNewInstanceReplay() {
        persistence.upsert("addr-A", "pub-A", new BigDecimal("1000"), 400L);

        // 模拟重启：同一库上新建 persistence 实例（init → replay），水位仍可读
        ValidatorSetPersistence fresh = new ValidatorSetPersistence(jdbc, registry, staking);
        fresh.init();
        assertNotNull(registry.getValidator("addr-A"), "重放仍注册该验证人");
        assertEquals(400L, fresh.lastNonce("addr-A"), "重启后重放护栏水位保持");
    }
}
