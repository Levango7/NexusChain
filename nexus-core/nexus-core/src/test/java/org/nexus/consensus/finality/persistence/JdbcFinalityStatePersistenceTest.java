package org.nexus.consensus.finality.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRecord;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-015 Phase 6A：JDBC 最终性存储与 FinalityGadget 的重启恢复闭环。
 *
 * <p>不同于 {@link FinalityStatePersistenceTest} 的纯内存存储，本测试使用真实
 * H2 JDBC 存储（同一 DataSource 新建 store 模拟进程重启），验证已最终化检查点
 * 和投票权重确实从数据库恢复，而不是只验证内存 Map 的复制。</p>
 */
class JdbcFinalityStatePersistenceTest {

    private static final byte[] CP1 = new byte[32];

    private JdbcTemplate jdbc;
    private ValidatorRegistry registry;
    private StakingService staking;
    private final Map<String, org.nexus.crypto.ed25519.Ed25519KeyPair> keys = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:finality_gadget_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
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
        org.nexus.crypto.ed25519.Ed25519KeyPair keyPair =
                org.nexus.crypto.ed25519.Ed25519.generateKeyPair();
        keys.put(address, keyPair);
        registry.register(address, org.apache.commons.codec.binary.Hex.encodeHexString(
                keyPair.getPublicKey().getEncoded()), new BigDecimal(stake), 0.1);
        Validator validator = registry.getValidator(address);
        validator.setStatus(ValidatorStatus.ACTIVE);
        staking.stake(address, new BigDecimal(stake));
    }

    private Vote vote(String validator, long epoch, byte[] checkpoint) {
        org.nexus.crypto.ed25519.Ed25519KeyPair keyPair = keys.get(validator);
        byte[] payload = ByteBuffer.allocate(8 + checkpoint.length).putLong(epoch).put(checkpoint).array();
        try {
            byte[] signature = keyPair.getPrivateKey().sign(payload);
            return new Vote(epoch, checkpoint, validator, signature,
                    keyPair.getPublicKey().getEncoded());
        } catch (Exception e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    @Test
    void finalizedCheckpointSurvivesRestartThroughJdbcStore() {
        JdbcFinalityStateStore store = new JdbcFinalityStateStore(jdbc);
        store.init();

        FinalityGadget node1 = new FinalityGadget(registry, staking, store);
        node1.submitVote(vote("v1", 1, CP1));
        node1.submitVote(vote("v2", 1, CP1));
        assertTrue(node1.isFinalized(1, CP1), "2/3 权重后 epoch1 应最终化");

        // 模拟进程重启：同一 DataSource 上新建 store，init 从 finality_* 表重载缓存。
        JdbcFinalityStateStore restartedStore = new JdbcFinalityStateStore(jdbc);
        restartedStore.init();
        FinalityGadget node2 = new FinalityGadget(registry, staking, restartedStore);

        assertTrue(node2.isFinalized(1, CP1), "重启后已最终化检查点必须从 JDBC 恢复");
        FinalityRecord record = node2.getEpochProgress(1);
        assertEquals(new BigDecimal("600"), record.getVotedWeight(), "恢复后投票权重一致");
        assertEquals(2, restartedStore.loadVoters(1, CP1).size(), "投票者集合应完整恢复");
    }
}
