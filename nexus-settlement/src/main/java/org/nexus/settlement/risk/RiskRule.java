package org.nexus.settlement.risk;

/**
 * 风控规则接口。
 * <p>
 * 单条规则的判定契约：返回 true 表示拦截该交易。
 * </p>
 */
public interface RiskRule {

    /**
     * 获取规则 ID。
     *
     * @return 规则 ID
     */
    String getRuleId();

    /**
     * 检查交易是否命中本规则。
     *
     * @param transaction 待检查交易
     * @return true 表示拦截，false 表示放行
     */
    boolean check(Object transaction);
}