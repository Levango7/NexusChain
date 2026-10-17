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
        return freshH2Harness(tag, 300L).allocator();
    }

    /** 测试夹具：分配器 + 同一份库的 JdbcTemplate + 同库第二个分配器（模拟另一副本）。 */
    public record H2Harness(NonceAllocator allocator, JdbcTemplate jdbc,
                            java.util.function.Supplier<NonceAllocator> replicaFactory) {

        /** 再要一个分配器实例：同一个库、不同 JVM bean —— 就是 prod 三副本的形状。 */
        public NonceAllocator replica() {
            return replicaFactory.get();
        }

        /** 把该地址的校正租约拨到"早已过期"，等价于"过了一个 reconcile 周期"。 */
        public void forceReconcileDue(String address) {
            int n = jdbc.update("UPDATE nonce_allocation SET last_reconciled_at = ? WHERE address = ?",
                    java.sql.Timestamp.from(java.time.Instant.EPOCH), address);
            if (n == 0) {
                throw new IllegalStateException("forceReconcileDue 没命中任何行: address=" + address);
            }
        }

        /** 直读库里的水位（不经 allocator，避免"自己测自己"）。 */
        public long watermark(String address) {
            Long v = jdbc.queryForObject(
                    "SELECT next_nonce FROM nonce_allocation WHERE address = ?", Long.class, address);
            return v == null ? -1L : v;
        }
    }

    public static H2Harness freshH2Harness(String tag, long reconcileIntervalSeconds) {
        return freshH2HarnessInternal(tag, reconcileIntervalSeconds);
    }

    private static H2Harness freshH2HarnessInternal(String tag, long reconcileIntervalSeconds) {
        try {
            DriverManagerDataSource ds = new DriverManagerDataSource();
            ds.setDriverClassName("org.h2.Driver");
            ds.setUrl("jdbc:h2:mem:" + tag + "_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            ds.setUsername("sa");
            ds.setPassword("");

            // 按**真实迁移顺序**应用 V3 → V4：跑的就是 prod 会跑的那两支脚本，
            // 而不是自己另写一份等价 DDL（否则 CI 里 Flyway 应用过的形状与本地测试的形状会漂移）。
            JdbcTemplate tpl = new JdbcTemplate(ds);
            applyMigration(tpl, "/db/migration/V3__nonce_allocation.sql");
            applyMigration(tpl, "/db/migration/V4__nonce_allocation_reconcile.sql");

            DataSourceTransactionManager txm = new DataSourceTransactionManager(ds);
            return new H2Harness(new NonceAllocator(tpl, txm, reconcileIntervalSeconds), tpl,
                    () -> new NonceAllocator(tpl, txm, reconcileIntervalSeconds));
        } catch (Exception e) {
            throw new IllegalStateException("测试用 H2 nonce 库初始化失败: tag=" + tag, e);
        }
    }

    private static void applyMigration(JdbcTemplate tpl, String resource) throws java.io.IOException {
        String ddl;
        try (InputStream in = NonceTestDbs.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " 不在 classpath 上（迁移没进 main/resources？）");
            }
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 剥掉 -- 注释行后执行（两份脚本主体各一条语句）
        tpl.execute(ddl.lines()
                .filter(l -> !l.strip().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .strip());
    }

    /** 组装一个 code=2000 的链上 nonce 响应（Gson JsonObject，与 NodeController 返回形状一致）。 */
    public static com.google.gson.JsonObject chainNonce(long value) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
        obj.addProperty("code", 2000);
        obj.addProperty("data", value);
        return obj;
    }
}
