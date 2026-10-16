package org.nexus.signing.pool;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 测试夹具：用<b>仓库里那份真实的 V3 迁移脚本</b>在 H2(MODE=MySQL) 上建一个独立的
 * {@code nonce_allocation} 表，并返回跑在其上的真实 {@link NonceAllocator}。
 *
 * <p>给 {@code TxControllerTest} / {@code SigningTccActionTest} 用：这两处测的是控制器与 TCC
 * 参与方的编排，但 nonce 发放已改为"共享原子认领"，把 {@code claim} 打桩成常数会让
 * "首次播种走链上 RPC、之后走 DB 递增"这条链路失去验证。用真分配器 + 真 H2 才能保住它。</p>
 *
 * <p>{@code tag} 显式传入是为了让每次调用得到<b>互不共享</b>的库（每个测试方法一份水位），
 * 否则同名 in-mem 库会跨用例累积、用例间互相影响。</p>
 */
public final class NonceTestDbs {

    private NonceTestDbs() {
    }

    /** 建一个全新的库并返回其上的分配器；{@code tag} 决定库名（每个用例给不同值）。 */
    public static NonceAllocator freshH2Allocator(String tag) {
        try {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("org.h2.Driver");
            ds.setUrl("jdbc:h2:mem:" + tag + "_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            ds.setUsername("sa");
            ds.setPassword("");

            String ddl;
            try (InputStream in = NonceTestDbs.class.getResourceAsStream("/db/migration/V3__nonce_allocation.sql")) {
                if (in == null) {
                    throw new IllegalStateException("V3__nonce_allocation.sql 不在 classpath 上（迁移没进 main/resources？）");
                }
                ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // 剥掉 -- 注释行后执行（脚本主体只有一条 CREATE TABLE）
            new JdbcTemplate(ds).execute(ddl.lines()
                    .filter(l -> !l.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"))
                    .strip());

            return new NonceAllocator(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
        } catch (Exception e) {
            throw new IllegalStateException("测试用 H2 nonce 库初始化失败: tag=" + tag, e);
        }
    }

    /** 组装一个 code=2000 的链上 nonce 响应（Gson JsonObject，与 NodeController 返回形状一致）。 */
    public static com.google.gson.JsonObject chainNonce(long value) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("code", 2000);
        obj.addProperty("data", value);
        return obj;
    }
}
