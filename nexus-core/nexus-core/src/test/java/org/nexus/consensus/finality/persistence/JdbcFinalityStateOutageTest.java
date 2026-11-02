package org.nexus.consensus.finality.persistence;

import org.apache.commons.codec.binary.Hex;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRecord;
import org.nexus.consensus.finality.SignatureAggregator;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-016 Phase 6B：JDBC 连接短时不可用时的最终性写路径。
 *
 * <p>用可切换的 DataSource 模拟 PostgreSQL 短暂断连：验证写投票失败时不会
 * 在内存留下幽灵投票者，连接恢复后同一张票可重试，并且最终化只在数据库
 * 确认后对外可见。</p>
 */
class JdbcFinalityStateOutageTest {

    private static final byte[] CP1 = new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};

    private ToggleDataSource dataSource;
    private JdbcTemplate jdbc;
    private ValidatorRegistry registry;
    private StakingService staking;
    private final Map<String, Ed25519KeyPair> validatorKeys = new HashMap<>();

    @BeforeEach
    void setUp() {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:finality_outage_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        h2.setUser("sa");
        h2.setPassword("");
        dataSource = new ToggleDataSource(h2);
        jdbc = new JdbcTemplate(dataSource);

        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        injectRegistryIntoStaking();
        addValidator("v1", 300);
        addValidator("v2", 300);
        addValidator("v3", 300);
    }

    private void injectRegistryIntoStaking() {
        try {
            var field = StakingServiceImpl.class.getDeclaredField("validatorRegistry");
            field.setAccessible(true);
            field.set(staking, registry);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void addValidator(String address, int stake) {
        Ed25519KeyPair keyPair = Ed25519.generateKeyPair();
        validatorKeys.put(address, keyPair);
        registry.register(address, Hex.encodeHexString(keyPair.getPublicKey().getEncoded()),
                new BigDecimal(stake), 0.1);
        Validator validator = registry.getValidator(address);
        validator.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(address, new BigDecimal(stake));
    }

    private Vote vote(String validator, long epoch, byte[] checkpoint) {
        Ed25519KeyPair keyPair = validatorKeys.get(validator);
        byte[] publicKey = keyPair.getPublicKey().getEncoded();
        Vote unsigned = new Vote(epoch, checkpoint, validator, new byte[0], publicKey);
        try {
            byte[] signature = keyPair.getPrivateKey().sign(unsigned.signingPayload());
            return new Vote(epoch, checkpoint, validator, signature, publicKey);
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    private SignatureAggregator passThroughAggregator() {
        return new SignatureAggregator() {
            @Override
            public AggregatedSignature aggregate(java.util.List<Vote> votes) {
                final int count = votes == null ? 0 : votes.size();
                return new AggregatedSignature() {
                    @Override public byte[] compressed() { return new byte[0]; }
                    @Override public int signerCount() { return count; }
                };
            }

            @Override
            public boolean verifyAggregate(java.util.List<Vote> votes, AggregatedSignature aggregated) {
                return true;
            }
        };
    }

    @Test
    void jdbcOutageDoesNotPoisonMemoryAndRecoversOnRetry() {
        JdbcFinalityStateStore store = new JdbcFinalityStateStore(jdbc);
        store.init();
        FinalityGadget gadget = new FinalityGadget(registry, staking, store);
        gadget.setSignatureAggregator(passThroughAggregator());

        dataSource.setDown(true);
        assertThrows(RuntimeException.class,
                () -> gadget.submitVote(vote("v1", 1, CP1)),
                "连接不可用时写投票必须失败，不能静默丢票");

        // 失败写不能污染内存缓存；否则重试会被当成重复票，数据库永远缺这一票。
        assertFalse(gadget.isFinalized(1, CP1));
        assertEquals(0, gadget.getFinality(1, CP1).getVotedWeight().signum());
        assertTrue(store.loadVoters(1, CP1).isEmpty());

        // 恢复连接后先确认故障期间没有偷偷写入投票行，再执行重试。
        dataSource.setDown(false);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM finality_votes", Integer.class));

        FinalityRecord accepted = gadget.submitVote(vote("v1", 1, CP1));
        assertEquals(new BigDecimal("300"), accepted.getVotedWeight());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM finality_votes", Integer.class));

        FinalityRecord finalized = gadget.submitVote(vote("v2", 1, CP1));
        assertTrue(finalized.isFinalized());
        assertTrue(store.isFinalized(1, CP1));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM finality_votes", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM finality_checkpoints", Integer.class));
    }

    /** 可切换“数据库不可用”的 DataSource；只影响新连接获取，不改变 H2 数据。 */
    private static final class ToggleDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private volatile boolean down;

        private ToggleDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        private void setDown(boolean down) {
            this.down = down;
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (down) {
                throw new SQLException("simulated database outage");
            }
            return delegate.getConnection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            if (down) {
                throw new SQLException("simulated database outage");
            }
            return delegate.getConnection(username, password);
        }
    }
}
