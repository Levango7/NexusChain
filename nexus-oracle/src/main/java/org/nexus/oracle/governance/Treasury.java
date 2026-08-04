package org.nexus.oracle.governance;

import java.math.BigDecimal;
import java.util.List;

/**
 * 国库服务。
 *
 * <p>管理链上社区国库的余额、支出与历史记录。所有支出必须
 * 关联一个已通过的 {@code TREASURY_SPEND} 类型提案，确保可审计。
 */
public interface Treasury {

    /**
     * 当前国库余额。
     *
     * @return 余额（以原生代币计）
     */
    BigDecimal balance();

    /**
     * 国库支出。
     *
     * @param amount     支出金额
     * @param to         收款地址
     * @param proposalId 关联提案 ID（必须为已通过的 TREASURY_SPEND 提案）
     * @return 是否成功支出
     */
    boolean spend(BigDecimal amount, String to, String proposalId);

    /**
     * 获取国库支出历史。
     *
     * @return 支出记录列表，每条记录为字段名 → 值的映射
     */
    List<java.util.Map<String, Object>> getHistory();

    /**
     * 获取单笔支出详情。
     *
     * @param spendId 支出 ID
     * @return 支出详情；不存在时返回 {@code null}
     */
    java.util.Map<String, Object> getSpend(String spendId);
}