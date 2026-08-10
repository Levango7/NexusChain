package org.nexus.config;

import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.io.IOUtils;
import org.nexus.core.BlockChainOptional;
import org.nexus.crypto.KeyPair;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.core.Block;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.nexus.core.RDBMSBlockChainImpl;

public class TestConfig {
    // H2 内存数据库，启用 PostgreSQL 兼容模式以支持 bytea/int4/int8/smallint/ON CONFLICT 等语法
    private static String testDBURL = "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";


    @Bean
    public BasicDataSource basicDataSource() {
        BasicDataSource ds = new BasicDataSource();
        ds.setUrl(testDBURL);
        ds.setDriverClassName("org.h2.Driver");
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setInitialSize(1);
        ds.setMaxTotal(100);
        ds.setMaxIdle(5);
        return ds;
    }

    @Bean
    public PlatformTransactionManager getTransactionManager(BasicDataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public JdbcTemplate getJDBCTemplate(BasicDataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate();
        jdbcTemplate.setDataSource(dataSource);
        return jdbcTemplate;
    }

    @Bean
    public JSONEncodeDecoder encodeDecoder() {
        return new JSONEncodeDecoder();
    }

    @Bean
    @Scope("prototype")
    public Block getGenesis() throws Exception {
        Resource resource = new ClassPathResource("genesis/NexusChain-test-genesis.json");
        return encodeDecoder().decodeBlock(IOUtils.toByteArray(resource.getInputStream()));
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
        TransactionTemplate tmpl = new TransactionTemplate();
        tmpl.setTransactionManager(manager);
        return tmpl;
    }

    protected void clearData(JdbcTemplate jdbcTemplate) {
        // 使用 drop table if exists 而非 delete from，避免表不存在时报错（H2 内存库初始无表）
        jdbcTemplate.batchUpdate("drop table if exists header",
                "drop table if exists transaction",
                "drop table if exists transaction_index",
                "drop table if exists account",
                "drop table if exists incubator_state");
    }

    @Bean
    public RDBMSBlockChainImpl getRDBMSBlockChainImpl(JdbcTemplate tpl, TransactionTemplate txtmpl, Block genesis, ApplicationContext ctx, BasicDataSource basicDataSource) throws Exception {
        return new RDBMSBlockChainImpl(tpl, txtmpl, genesis, ctx, "", true, basicDataSource);
    }

    @Bean
    public BlockChainOptional blockChainOptional(JdbcTemplate template, Block genesis) {
        return new BlockChainOptional(template, genesis);
    }

    @Bean
    @Scope("prototype")
    public KeyPair getKeyPair() {
        return Ed25519.generateKeyPair();
    }
}
