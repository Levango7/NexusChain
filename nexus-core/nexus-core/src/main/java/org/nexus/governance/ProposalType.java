package org.nexus.governance;

/**
 * 治理提案类型枚举。
 *
 * @since 1.2
 */
public enum ProposalType {
    /** 修改链参数 */
    PARAMETER_CHANGE,
    /** 软分叉升级 */
    SOFTWARE_UPGRADE,
    /** 资金国库支出 */
    TREASURY_SPEND,
    /** 自定义 */
    CUSTOM
}