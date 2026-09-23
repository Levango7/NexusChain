package org.nexus.gateway.export;

/**
 * 数据导出格式枚举。
 *
 * <p>定义支持的导出文件格式：
 * <ul>
 *   <li>{@link #CSV} — CSV 格式（逗号分隔值）</li>
 *   <li>{@link #JSON} — JSON 格式（结构化数据）</li>
 * </ul>
 */
public enum DataExportFormat {
    /** CSV 格式 */
    CSV,
    /** JSON 格式 */
    JSON
}