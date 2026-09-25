package org.nexus.gateway.fundreport;

/**
 * 资金报表类型枚举。
 *
 * <p>定义报表的统计周期类型，由 {@link FundReportService} 根据类型生成不同时间范围的报表。</p>
 */
public enum ReportType {
    /** 日报 — 统计单日资金流水 */
    DAILY,
    /** 周报 — 统计一周资金流水 */
    WEEKLY,
    /** 月报 — 统计一月资金流水 */
    MONTHLY
}