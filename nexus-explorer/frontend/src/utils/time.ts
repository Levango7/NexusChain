/**
 * 时间格式化工具。
 *
 * 背景（2026-09-16 审查 P1）：此前 `HomePage.formatTime` 手写实现，
 * 返回硬编码英文 `"5s ago"` / `"30m ago"` / `"30h ago"`，存在三个问题：
 *   1. 未走 i18n —— 中文界面里混入英文
 *   2. 无「天」以上单位 —— 30 天前也显示 "720h ago"
 *   3. 未处理时钟偏移 —— 时间戳在未来时显示 "-5s ago"
 * 改用 `Intl.RelativeTimeFormat` 后，语言、单复数、单位进位、未来/过去方向
 * 全部由平台处理。
 */

/**
 * 把 Unix 秒时间戳格式化为相对当前时间的可读字符串。
 *
 * @param timestampSeconds 目标时间（Unix 秒）
 * @param locale           BCP-47 语言标签（如 `"zh"` / `"en"`）
 * @param nowMs            当前时间（毫秒），仅测试注入
 */
export function formatRelativeTime(
  timestampSeconds: number,
  locale: string,
  nowMs: number = Date.now(),
): string {
  if (!Number.isFinite(timestampSeconds)) return "—";

  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const diffSec = Math.floor(nowMs / 1000) - timestampSeconds;
  // 负号表示「过去」；时间戳在未来时 diffSec 为负，自动转为「之后」
  const abs = Math.abs(diffSec);

  if (abs < 60) return rtf.format(-diffSec, "second");
  if (abs < 3600) return rtf.format(-Math.trunc(diffSec / 60), "minute");
  if (abs < 86400) return rtf.format(-Math.trunc(diffSec / 3600), "hour");
  if (abs < 2592000) return rtf.format(-Math.trunc(diffSec / 86400), "day");
  if (abs < 31536000) return rtf.format(-Math.trunc(diffSec / 2592000), "month");
  return rtf.format(-Math.trunc(diffSec / 31536000), "year");
}

/**
 * 把 Unix 秒时间戳格式化为本地绝对时间字符串。
 * 用于详情页（需要精确时间而非相对时间）。
 */
export function formatAbsoluteTime(timestampSeconds: number, locale: string): string {
  if (!Number.isFinite(timestampSeconds) || timestampSeconds <= 0) return "—";
  return new Date(timestampSeconds * 1000).toLocaleString(locale);
}
