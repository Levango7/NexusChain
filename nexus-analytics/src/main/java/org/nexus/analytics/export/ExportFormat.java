package org.nexus.analytics.export;

/**
 * 数据导出格式。
 */
public enum ExportFormat {

    /** 通用 CSV，适合表格类工具 */
    CSV,

    /** JSON Lines / 嵌套 JSON，适合程序消费 */
    JSON,

    /** Apache Parquet，列式存储，适合大数据分析 */
    PARQUET
}