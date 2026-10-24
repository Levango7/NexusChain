package org.nexus.consensus.finality.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ValidatorSetPersistence} 原始签名载荷（signed_payload）测试 —— PLAN-001
 * 【切片 4：回放签名语义】。
 *
 * <p>覆盖：列增量 DDL 可建、载荷写入/读取、**无签名 upsert 不清空已存载荷**
 * （COALESCE 语义——老消息/回放自身到达时不得把签名原文抹掉）、
 * 以及 limit 参数生效。回放侧"逐字节复用"由
 * {@code SyncManagerValidatorSetRelayTest} 的接线用例断言。</p>
 */
class ValidatorSetPersistenceSignedPayloadTest {

    private JdbcTemplate jdbc;
    private ValidatorRegistry registry;
    private StakingServiceImpl staking;
    private ValidatorSetPersistence persistence;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:vsetpayload_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl();
        persistence = new ValidatorSetPersistence(jdbc, registry, staking);
        persistence.init();
    }

    @Test
    void signedPayloadStoredLoadedAndLimitApplied() {
        persistence.upsert("addr-A", "pub-A", new BigDecimal("1000"), 100L, "cGF5bG9hZC1B");
        persistence.upsert("addr-B", "pub-B", new BigDecimal("1000"), 200L, "cGF5bG9hZC1C");
        persistence.upsert("addr-C", "pub-C", new BigDecimal("1000"), 300L); // 无载荷

        Map<String, String> loaded = persistence.loadSignedPayloads(10);
        assertEquals(2, loaded.size());
        assertEquals("cGF5bG9hZC1B", loaded.get("addr-A"));
        assertEquals("cGF5bG9hZC1C", loaded.get("addr-B"));
        assertFalse(loaded.containsKey("addr-C"), "无载荷行不得出现");

        Map<String, String> limited = persistence.loadSignedPayloads(1);
        assertEquals(1, limited.size(), "limit 生效");
    }

    @Test
    void unsignedUpsertMustNotClearStoredPayload() {
        persistence.upsert("addr-A", "pub-A", new BigDecimal("1000"), 100L, "cGF5bG9hZC1B");
        // 无签名路径（老调用方 / 无签名回放到达）：载荷必须保留（COALESCE）
        persistence.upsert("addr-A", "pub-A2", new BigDecimal("1000"), 150L);
        assertEquals("cGF5bG9hZC1B", persistence.loadSignedPayloads(10).get("addr-A"));

        // 带新载荷：覆盖为最新
        persistence.upsert("addr-A", "pub-A3", new BigDecimal("1000"), 200L, "bmV3LXBheWxvYWQ=");
        assertEquals("bmV3LXBheWxvYWQ=", persistence.loadSignedPayloads(10).get("addr-A"));
    }

    @Test
    void payloadSurvivesNewInstanceReplay() {
        persistence.upsert("addr-A", "pub-A", new BigDecimal("1000"), 400L, "cGF5bG9hZC1B");
        ValidatorSetPersistence fresh = new ValidatorSetPersistence(jdbc, registry, staking);
        fresh.init(); // init → replay（不触碰 signed_payload）
        Map<String, String> loaded = fresh.loadSignedPayloads(10);
        assertTrue(loaded.containsKey("addr-A"), "重启后签名载荷仍可读（回放复用前提）");
        assertEquals("cGF5bG9hZC1B", loaded.get("addr-A"));
    }
}
