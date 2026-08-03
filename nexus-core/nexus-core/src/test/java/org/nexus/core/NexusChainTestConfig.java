package org.nexus.core;

import org.apache.commons.dbcp2.BasicDataSource;
import org.nexus.config.TestConfig;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

public class NexusChainTestConfig extends TestConfig {
    @Bean
    @Scope("prototype")
    public RDBMSBlockChainImpl getRDBMSBlockChainImpl(JdbcTemplate tpl, TransactionTemplate txtmpl, Block genesis, ApplicationContext ctx, BasicDataSource basicDataSource) throws Exception {
        clearData(tpl);
        return new RDBMSBlockChainImpl(tpl, txtmpl, genesis, ctx, "", true, basicDataSource);
    }

}
