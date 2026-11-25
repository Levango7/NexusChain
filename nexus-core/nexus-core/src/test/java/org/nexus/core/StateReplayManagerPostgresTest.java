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
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.SQLException;
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
    private boolean stopAfterAccountWrite;

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
        stopAfterAccountWrite = false;
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

        merkleRule = mock(MerkleRule.class);
        replayManager = newReplayManager();

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

    private StateReplayManager newReplayManager() {
        AccountDB accountDB = new AccountDB() {
            @Override
            public int[] insertAccountList(List<Object[]> rows) {
                int[] written = super.insertAccountList(rows);
                if (stopAfterAccountWrite) {
                    try {
                        // 关闭当前事务连接，模拟首表写后真实 JDBC 断连；不破坏 PG schema。
                        DataSourceUtils.getConnection(dataSource).close();
                    } catch (SQLException error) {
                        throw new IllegalStateException(error);
                    }
                }
                return written;
            }
        };
        IncubatorDB incubatorDB = new IncubatorDB();
        ReflectionTestUtils.setField(accountDB, "tmpl", jdbc);
        ReflectionTestUtils.setField(incubatorDB, "tmpl", jdbc);

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
        return (StateReplayManager) proxyFactory.getProxy();
    }

    @Test
    void secondStoreFailureRollsBackAccountRowsOnPostgres() {
        jdbc.execute("drop table incubator_state");

        assertThrows(IllegalStateException.class, () -> replayManager.replayBlock(block));

        assertEquals(0, jdbc.queryForObject("select count(*) from account", Integer.class),
                "真实 PostgreSQL 下第二表失败也必须回滚首表");
    }

    @Test
    void databaseOutageRollsBackAndRecoversOnPostgres() throws Exception {
        when(merkleRule.verifyStateRoots(eq(block), anyList(), anyList(), anyList())).thenReturn(Result.SUCCESS);
        stopAfterAccountWrite = true;

        // 断库时业务写失败与 JDBC rollback 失败都属于 fail-closed；后者由
        // TransactionSystemException 包装，不能误要求只有一种异常类型。
        assertThrows(RuntimeException.class, () -> replayManager.replayBlock(block));

        // 恢复请求改由新事务管理器执行，不能复用已断开的事务上下文。
        stopAfterAccountWrite = false;
        replayManager = newReplayManager();
        awaitPostgresReady();
        assertEquals(0, jdbc.queryForObject("select count(*) from account", Integer.class),
                "首表写后断库也不得留下半提交");
        StateReplayManager.ReplayResult recovered = replayManager.replayBlock(block);
        assertTrue(recovered.isVerified());
        assertEquals(1, jdbc.queryForObject("select count(*) from account", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from incubator_state", Integer.class));
    }

    private static void awaitPostgresReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (true) {
            try {
                jdbc.queryForObject("select 1", Integer.class);
                return;
            } catch (RuntimeException error) {
                if (System.currentTimeMillis() >= deadline) {
                    throw error;
                }
                Thread.sleep(100L);
            }
        }
    }

    @Test
    void successfulReplayCommitsBothStoresOnPostgres() throws Exception {
        when(merkleRule.verifyStateRoots(eq(block), anyList(), anyList(), anyList())).thenReturn(Result.SUCCESS);

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
