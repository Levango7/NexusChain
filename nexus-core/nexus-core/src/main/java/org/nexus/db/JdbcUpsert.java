package org.nexus.db;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 可移植的 JDBC upsert 工具（PostgreSQL + H2 PostgreSQL 兼容模式）。
 *
 * <p><b>为什么不用 MERGE / ON CONFLICT DO UPDATE</b>：</p>
 * <ul>
 *   <li>{@code MERGE INTO ... KEY(...)} 是 <b>H2 专有语法</b>，PostgreSQL 会报
 *       {@code bad SQL grammar} —— 测试跑 H2 时全绿、生产切 PG 才炸。</li>
 *   <li>{@code INSERT ... ON CONFLICT DO UPDATE} 自 PostgreSQL 9.5 起支持，但
 *       H2 2.2.224（本项目测试基线）不支持，异常又常被上层 {@code catch} 吞掉，
 *       导致测试覆盖形同虚设。</li>
 * </ul>
 *
 * <p>本工具只用两边都支持的 SQL 组合：<b>先 UPDATE；未命中则
 * {@code INSERT ... ON CONFLICT DO NOTHING}；若并发下被别的事务抢先插入
 * （影响行数为 0），再 UPDATE 一次</b>。</p>
 *
 * <p>与"INSERT 撞唯一键后 catch 再重试 UPDATE"相比，{@code DO NOTHING} 不会让
 * PostgreSQL 把当前事务置为 aborted，因此调用方即使处于显式事务中也能安全重试。</p>
 *
 * <p><b>并发边界</b>：两个事务同时写同一主键时，最终值由后提交者决定
 * （last-writer-wins）；本工具不提供乐观锁或版本列。</p>
 *
 * @since 2.74
 */
public final class JdbcUpsert {

    private JdbcUpsert() {
    }

    /**
     * 可移植 upsert：存在则更新，不存在则插入。
     *
     * @param jdbc       JDBC 模板
     * @param updateSql  按主键更新的 SQL（必须包含全部可更新列与主键条件）
     * @param updateArgs 更新参数（顺序与 {@code updateSql} 的占位符一致，主键在最后）
     * @param insertSql  插入 SQL（不含 {@code ON CONFLICT} 子句）
     * @param insertArgs 插入参数（顺序与 {@code insertSql} 的占位符一致）
     */
    public static void upsert(JdbcTemplate jdbc,
                              String updateSql, Object[] updateArgs,
                              String insertSql, Object[] insertArgs) {
        if (jdbc.update(updateSql, updateArgs) > 0) {
            return;
        }
        int inserted = jdbc.update(insertSql + " ON CONFLICT DO NOTHING", insertArgs);
        if (inserted == 0) {
            // 并发插入竞争：另一事务已提交同一主键，退回更新。
            jdbc.update(updateSql, updateArgs);
        }
    }

    /**
     * 幂等插入：主键已存在时静默忽略。
     *
     * <p>适用于"只记录键是否存在"的表（例如已消费重放键、已投票记录）。</p>
     */
    public static void insertIgnore(JdbcTemplate jdbc, String insertSql, Object[] args) {
        jdbc.update(insertSql + " ON CONFLICT DO NOTHING", args);
    }
}
