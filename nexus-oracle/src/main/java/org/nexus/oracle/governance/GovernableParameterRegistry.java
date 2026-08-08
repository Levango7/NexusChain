package org.nexus.oracle.governance;

import java.util.Map;

/**
 * 可治理参数注册表。
 *
 * <p>管理链上可治理参数的当前值、校验与变更应用。
 * {@code PARAMETER_CHANGE} 类型提案通过此注册表落地参数变更。
 *
 * <p>当前为进程内存储，后续接入链上治理合约时替换为带范围校验
 * 与持久化的实现（参考 {@code nexus-core} 的 {@code GovernableParameterRegistry}）。
 *
 * @since 1.9.2
 */
public interface GovernableParameterRegistry {

    /**
     * 校验参数值是否合法。
     *
     * @param paramName 参数名
     * @param value     参数值（字符串表示）
     * @return 合法返回 true；参数名空或值为 null 返回 false
     */
    boolean validate(String paramName, String value);

    /**
     * 设置参数值（应用变更）。
     *
     * @param paramName 参数名
     * @param value     参数值（字符串表示）
     * @return 应用成功返回 true；校验失败返回 false
     */
    boolean setParameter(String paramName, String value);

    /**
     * 查询参数当前值。
     *
     * @param paramName 参数名
     * @return 参数值；不存在或参数名为 null 时返回 {@code null}
     */
    Object getParameter(String paramName);

    /**
     * 捕获当前所有参数快照（用于变更失败回滚）。
     *
     * @return 参数名 → 值 的快照副本
     */
    Map<String, Object> snapshot();

    /**
     * 从快照恢复参数（回滚）。
     *
     * @param snapshot 快照；为 null 时清空所有参数
     */
    void restore(Map<String, Object> snapshot);
}