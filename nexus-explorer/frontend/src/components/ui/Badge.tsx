import React from "react";

/**
 * Badge — 通用状态徽标。
 *
 * 用于状态标签（SUCCEEDED / FAILED / PROCESSING / active / inactive 等），
 * 通过 tone 映射到语义色 token，避免散落 text-emerald-400 / text-red-400 等。
 */
export type BadgeTone =
  | "neutral"
  | "primary"
  | "success"
  | "warning"
  | "danger";

export interface BadgeProps {
  tone?: BadgeTone;
  /** 是否实底（否则仅描边 + 浅底）。 */
  solid?: boolean;
  children?: React.ReactNode;
  className?: string;
}

const TONE_TEXT: Record<BadgeTone, string> = {
  neutral: "text-muted",
  primary: "text-accent",
  success: "text-success",
  warning: "text-warn",
  danger: "text-danger",
};

const TONE_SOLID: Record<BadgeTone, string> = {
  neutral: "bg-surface-2 text-muted border border-border",
  primary: "bg-accent text-accent-on",
  success: "bg-success/15 text-success border border-success/30",
  warning: "bg-warn/15 text-warn border border-warn/30",
  danger: "bg-danger/15 text-danger border border-danger/30",
};

const TONE_SOFT: Record<BadgeTone, string> = {
  neutral: "bg-surface-2 text-muted",
  primary: "bg-accent-soft text-accent",
  success: "bg-success/10 text-success",
  warning: "bg-warn/10 text-warn",
  danger: "bg-danger/10 text-danger",
};

export const Badge: React.FC<BadgeProps> = ({
  tone = "neutral",
  solid = false,
  children,
  className = "",
}) => {
  const cls = solid
    ? TONE_SOLID[tone]
    : TONE_SOFT[tone] ?? TONE_TEXT[tone];
  return (
    <span
      className={`inline-flex items-center px-2 py-0.5 rounded-sm text-xs font-medium ${cls} ${className}`}
    >
      {children}
    </span>
  );
};

export default Badge;