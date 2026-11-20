package org.nexus.core;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.account.Account;
import org.nexus.core.account.AccountDB;
import org.nexus.core.incubator.Incubator;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实 PostgreSQL 上的 StateReplay 事务边界；Docker 不可用时明确跳过。 */
class StateReplayManagerPostgresTest {

    private static PostgreSQLContainer<?> postgres;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;

    private StateReplayManager replayManager;
    private MerkleRule merkleRule;
    private Block block;

    @BeforeAll
    static void startPostgres() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker 不可用，跳过真实 PostgreSQL StateReplay 测试");
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("drop table if exists account");
        jdbc.execute("drop table if exists incubator_state");
        jdbc.execute("""
                create table account (
                    id bytea primary key,
                    blockheight int4 not null,
                    pubkeyhash bytea not null,
                    nonce int8 not null,
                    balance int8 not null,
                    incubatecost int8,
                    mortgage int8,
                    vote int8 default 0 not null
                )
                """);
        jdbc.execute("""
                create table incubator_state (
                    id bytea primary key,
                    share_pubkeyhash bytea,
                    pubkeyhash bytea not null,
                    txid_issue bytea not null,
                    height int4 not null,
                    cost int8 not null,
                    interest_amount int8 not null,
                    share_amount int8,
                    last_blockheight_interest int4 not null,
                    last_blockheight_share int4
                )
                """);

        AccountDB accountDB = new AccountDB();
        IncubatorDB incubatorDB = new IncubatorDB();
        ReflectionTestUtils.setField(accountDB, "tmpl", jdbc);
        ReflectionTestUtils.setField(incubatorDB, "tmpl", jdbc);
        merkleRule = mock(MerkleRule.class);

        StateReplayManager target = new StateReplayManager();
        ReflectionTestUtils.setField(target, "merkleRule", merkleRule);
        ReflectionTestUtils.setField(target, "accountDB", accountDB);
        ReflectionTestUtils.setField(target, "incubatorDB", incubatorDB);
        ReflectionTestUtils.setField(target, "ctx", mock(ApplicationContext.class));

        TransactionInterceptor transactionInterceptor = new TransactionInterceptor();
        transactionInterceptor.setTransactionManager(new DataSourceTransactionManager(dataSource));
        transactionInterceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactionInterceptor);
        replayManager = (StateReplayManager) proxyFactory.getProxy();

        block = new Block();
        block.nHeight = 10L;
        block.body = List.of();
        Account account = new Account(10L, new byte[20], 1L, 100L, 0L, 0L, 0L);
        Incubator incubator = new Incubator(new byte[20], new byte[20], new byte[32],
                10L, 5L, 0L, 0L, 0L, 0L);
        when(merkleRule.validateMerkle(block.body, block.nHeight)).thenReturn(Map.of(
                "account", List.of(account),
                "incubator", List.of(incubator)));
    }

    @Test
    void secondStoreFailureRollsBackAccountRowsOnPostgres() {
        jdbc.execute("drop table incubator_state");

        assertThrows(IllegalStateException.class, () -> replayManager.replayBlock(block));

        assertEquals(0, jdbc.queryForObject("select count(*) from account", Integer.class),
                "真实 PostgreSQL 下第二表失败也必须回滚首表");
    }

    @Test
    void successfulReplayCommitsBothStoresOnPostgres() throws Exception {
        when(merkleRule.verifyStateRoots(eq(block), anyList(), anyList())).thenReturn(Result.SUCCESS);

        StateReplayManager.ReplayResult result = replayManager.replayBlock(block);

        assertTrue(result.isVerified());
        assertEquals(1, jdbc.queryForObject("select count(*) from account", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from incubator_state", Integer.class));

        replayManager.replayBlock(block);
        assertEquals(1, jdbc.queryForObject("select count(*) from account", Integer.class),
                "真实 PostgreSQL 下重复重放仍须幂等");
        assertEquals(1, jdbc.queryForObject("select count(*) from incubator_state", Integer.class));
    }
}
