import React from "react";
import { Inbox } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Loading } from "./Loading";

/**
 * Table — 通用表格组件。
 *
 * 特性：
 *   - 表头（columns 定义 label + key + 可选 render + 对齐）
 *   - 行（data 数组，按 rowKey 取唯一键）
 *   - 空状态（emptyState 或默认 Inbox 图标 + 文案）
 *   - 加载状态（loading=true 显示 Spinner 覆盖）
 *   - 行点击（onRowClick）
 *   - 颜色 / 间距 / 圆角 / 字体均引用 design tokens
 *
 * P2: 默认空状态文案 "暂无数据" 提取到 i18n（common.notFound 兜底 + emptyText 覆盖）。
 *     当调用方未传 emptyText 时，使用 i18n 的 common.notFound 作为兜底文案。
 */
export interface Column<T> {
  /** 列标识（取行数据字段名）。 */
  key: string;
  /** 表头文案。 */
  label: React.ReactNode;
  /** 自定义单元格渲染。 */
  render?: (row: T, index: number) => React.ReactNode;
  /** 列对齐。 */
  align?: "left" | "center" | "right";
  /** 列宽 Tailwind 类（如 w-32 / min-w-[200px]）。 */
  width?: string;
}

export type TableRow = Record<string, unknown>;

export interface TableProps<T extends TableRow> {
  /** 列定义。 */
  columns: Column<T>[];
  /** 行数据。 */
  data: T[];
  /** 行唯一键字段名（默认 "id"）。 */
  rowKey?: string;
  /** 加载状态。 */
  loading?: boolean;
  /** 空状态文案（默认使用 i18n common.notFound）。 */
  emptyText?: string;
  /** 自定义空状态节点。 */
  emptyState?: React.ReactNode;
  /** 行点击回调。 */
  onRowClick?: (row: T, index: number) => void;
  /** 额外 className。 */
  className?: string;
}

const ALIGN_CLASS: Record<NonNullable<Column<TableRow>["align"]>, string> = {
  left: "text-left",
  center: "text-center",
  right: "text-right",
};

export function Table<T extends TableRow>({
  columns,
  data,
  rowKey = "id",
  loading = false,
  emptyText,
  emptyState,
  onRowClick,
  className = "",
}: TableProps<T>) {
  const { t } = useTranslation();
  const resolvedEmptyText = emptyText ?? t("common.notFound");
  return (
    <div
      className={`relative bg-surface border border-border rounded-lg overflow-hidden ${className}`}
    >
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-surface-2">
            {columns.map((col) => (
              <th
                key={col.key}
                className={`px-4 py-2.5 text-xs font-semibold text-muted uppercase tracking-wide ${
                  ALIGN_CLASS[col.align ?? "left"]
                } ${col.width ?? ""}`}
              >
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-border-soft">
          {data.map((row, index) => (
            <tr
              key={String(row[rowKey] ?? index)}
              onClick={onRowClick ? () => onRowClick(row, index) : undefined}
              className={
                onRowClick
                  ? "cursor-pointer hover:bg-accent-soft transition-colors duration-base ease-standard"
                  : ""
              }
            >
              {columns.map((col) => (
                <td
                  key={col.key}
                  className={`px-4 py-3 text-fg-2 ${
                    ALIGN_CLASS[col.align ?? "left"]
                  } ${col.width ?? ""}`}
                >
                  {col.render
                    ? col.render(row, index)
                    : (row[col.key] as React.ReactNode)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>

      {/* 空状态 */}
      {!loading && data.length === 0 && (
        <div className="flex flex-col items-center justify-center py-12 text-muted">
          {emptyState ?? (
            <>
              <Inbox size={32} strokeWidth={1.5} className="mb-2 opacity-60" />
              <span className="text-sm">{resolvedEmptyText}</span>
            </>
          )}
        </div>
      )}

      {/* 加载覆盖 */}
      {loading && (
        <div className="absolute inset-0 flex items-center justify-center bg-surface/70 backdrop-blur-sm">
          <Loading />
        </div>
      )}
    </div>
  );
}

export default Table;