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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ValidatorSetPersistence} 重放回归测试（PLAN-020）。
 *
 * <p>回归目标：2026-10-22 的 3 节点验收发现，{@code replay()} 在
 * {@code RowCallbackHandler} 里多调了一次 {@code rs.next()} ⇒ **隔行丢失**：
 * 库里 3 个验证人只重放了 2 个 ⇒ 重启节点认不全 proposer ⇒
 * "unknown proposer (validator set not synchronized)" ⇒ 该节点无法采纳对端区块、
 * 永久卡在缺口高度。</p>
 *
 * <p>本测试锁定"**行数 ≥ 3 时必须全部重放**"（旧实现只重放隔行的那些）。</p>
 */
class ValidatorSetPersistenceReplayTest {

    private JdbcTemplate jdbc;
    private ValidatorRegistry registry;
    private StakingServiceImpl staking;
    private ValidatorSetPersistence persistence;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:vsetreplay_" + UUID.randomUUID().toString().replace("-", "")
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
    void everyValidatorRowIsReplayedNotEveryOtherRow() {
        for (int i = 1; i <= 5; i++) {
            persistence.upsert("addr-" + i, "pub-" + i, new BigDecimal("1000"));
        }
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM validators_synced", Integer.class));

        ValidatorSetPersistence fresh = new ValidatorSetPersistence(jdbc, registry);
        fresh.init();   // init() 会触发 replay()

        for (int i = 1; i <= 5; i++) {
            assertNotNull(registry.getValidator("addr-" + i),
                    "第 " + i + " 个验证人必须被重放（旧实现会隔行丢失）");
        }
        assertEquals(5, registry.getActiveValidators().size());
    }

    @Test
    void replayAlsoSeedsStakeSoTotalWeightIsCorrect() {
        // PLAN-021：StakingService.getStake() 只读本地账本（不回落注册表），
        // 所以重放若只注册不灌质押，节点的总权重就只算自己 ⇒ 永远达不到 2/3 阈值。
        for (int i = 1; i <= 3; i++) {
            persistence.upsert("addr-" + i, "pub-" + i, new BigDecimal("1000"));
        }
        ValidatorSetPersistence fresh = new ValidatorSetPersistence(jdbc, registry, staking);
        fresh.init();

        BigDecimal total = BigDecimal.ZERO;
        for (int i = 1; i <= 3; i++) {
            assertEquals(new BigDecimal("1000"), staking.getStake("addr-" + i),
                    "第 " + i + " 个验证人的质押额必须被灌进本地账本");
            total = total.add(staking.getStake("addr-" + i));
        }
        assertEquals(new BigDecimal("3000"), total);
    }

    @Test
    void replayIsIdempotent() {
        persistence.upsert("addr-x", "pub-x", new BigDecimal("1000"));
        persistence.replay();
        int before = registry.getActiveValidators().size();
        persistence.replay();
        assertEquals(before, registry.getActiveValidators().size(), "重复重放不得重复注册");
        assertTrue(registry.getValidator("addr-x") != null);
    }
}
