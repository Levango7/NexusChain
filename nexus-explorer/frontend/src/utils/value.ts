/**
 * 值展示辅助。
 *
 * 背景（2026-09-16 审查 P1）：React 把 `undefined` / `null` 子节点渲染为**空串**，
 * 于是 `{tx.fee} NEX` 在 fee 缺失时显示为「手续费 NEX」—— 用户无法区分
 * 「手续费为 0」与「字段缺失」；而模板字符串里的 `${block.size} 字节`
 * 则会渲染出字面量 "undefined"。两者在资金类界面都属于数据可信度问题。
 *
 * 统一用 `—` 显式表达缺失，避免歧义。
 */

/** 缺失值占位符。 */
export const EMPTY_PLACEHOLDER = "—";

/**
 * 值为 null / undefined / 空串时返回占位符，否则原样返回。
 *
 * @param value    待展示的值
 * @param fallback 占位符，默认 `"—"`
 */
export function orDash<T>(value: T | null | undefined, fallback = EMPTY_PLACEHOLDER): T | string {
  if (value === null || value === undefined || value === "") return fallback;
  return value;
}
