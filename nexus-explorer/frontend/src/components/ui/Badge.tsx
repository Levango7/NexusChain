import React from "react";
import { CheckCircle2, XCircle, AlertTriangle } from "lucide-react";

/**
 * Badge — 通用状态徽标。
 *
 * 用于状态标签（SUCCEEDED / FAILED / PROCESSING / active / inactive 等），
 * 通过 tone 映射到语义色 token，避免散落 text-emerald-400 / text-red-400 等。
 *
 * 2026-09-16 审查修复：
 *  1. 删除死代码 `TONE_TEXT` —— 原实现写作 `TONE_SOFT[tone] ?? TONE_TEXT[tone]`，
 *     而 `TONE_SOFT` 是覆盖全部 5 个 tone 的 Record，`??` 右侧永不求值。
 *  2. 浅底改用独立的语义浅底 token（`bg-success-soft` 等），不再用
 *     `bg-success/10` 这种「文字色 alpha 稀释」形式 —— 后者在浅色主题下
 *     会让底色趋近文字色，对比度反而随不透明度升高而恶化（实测 3.81:1）。
 *     新 token 已按 WCAG AA 核算（两主题各 tone 均 ≥4.5:1）。
 *  3. 按 DESIGN.md §4「状态标签：lucide 图标 + 文字（不只靠颜色）」补默认图标。
 *     纯颜色传达状态对色觉障碍用户不可用，故默认渲染图标；
 *     需要纯文字时传 `icon={null}`。
 */

export type BadgeTone = "neutral" | "primary" | "success" | "warning" | "danger";

export interface BadgeProps {
  tone?: BadgeTone;
  /** 带语义描边（强调态）；默认仅浅底。 */
  outlined?: boolean;
  /**
   * 图标。
   *  - 不传（默认）：使用该 tone 的默认图标（success/danger/warning 有，其余无）
   *  - 传 `null`：不渲染图标
   *  - 传 ReactNode：使用自定义图标
   */
  icon?: React.ReactNode | null;
  children?: React.ReactNode;
  className?: string;
}

/** 各 tone 的默认图标（仅状态类 tone 有）。 */
const TONE_DEFAULT_ICON: Partial<Record<BadgeTone, React.ReactNode>> = {
  success: <CheckCircle2 size={12} aria-hidden="true" />,
  warning: <AlertTriangle size={12} aria-hidden="true" />,
  danger: <XCircle size={12} aria-hidden="true" />,
};

const TONE_SOFT: Record<BadgeTone, string> = {
  neutral: "bg-surface-2 text-muted",
  primary: "bg-accent-soft text-accent",
  success: "bg-success-soft text-success",
  warning: "bg-warn-soft text-warn",
  danger: "bg-danger-soft text-danger",
};

const TONE_OUTLINED: Record<BadgeTone, string> = {
  neutral: "bg-surface-2 text-muted border border-border",
  primary: "bg-accent-soft text-accent border border-accent/30",
  success: "bg-success-soft text-success border border-success/30",
  warning: "bg-warn-soft text-warn border border-warn/30",
  danger: "bg-danger-soft text-danger border border-danger/30",
};

export const Badge: React.FC<BadgeProps> = ({
  tone = "neutral",
  outlined = false,
  icon,
  children,
  className = "",
}) => {
  const cls = outlined ? TONE_OUTLINED[tone] : TONE_SOFT[tone];
  const resolvedIcon = icon === undefined ? TONE_DEFAULT_ICON[tone] : icon;

  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-sm text-xs font-medium ${cls} ${className}`}
    >
      {resolvedIcon}
      {children}
    </span>
  );
};

export default Badge;
