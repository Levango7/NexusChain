package org.nexus.gateway.fundreport;

/**
 * 资金报表格式枚举。
 *
 * <p>定义报表内容的输出格式，支持 CSV 和 JSON 两种格式。
 * CSV 适合导出到电子表格，JSON 适合程序化处理。</p>
 */
public enum ReportFormat {
    /** CSV 格式 — 逗号分隔值，适合电子表格 */
    CSV,
    /** JSON 格式 — 结构化数据，适合程序化处理 */
    JSON
}