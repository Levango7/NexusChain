import React from "react";
import { Loader2 } from "lucide-react";

/**
 * Button — 通用按钮组件。
 *
 * 变体（variant）—— 与 DESIGN.md §4 对齐：
 *   - primary  ：主操作（accent-solid 实底 + accent-on 文字）
 *   - secondary：次操作（透明底 + accent 描边 + accent 文字）
 *   - danger   ：破坏性操作（danger-solid 实底 + danger-on 文字）
 *   - ghost    ：幽灵按钮（surface-2 底，hover 出 soft 背景）
 *
 * 尺寸（size）：sm / md / lg
 *   触摸目标（2026-09-16 审查 P1 修复）：
 *     sm = 36px（满足 WCAG 2.2 SC 2.5.8 AA 的 24px 下限，用于表格行内等紧凑场景）
 *     md = 44px、lg = 48px（满足 DESIGN.md §8 与 WCAG 2.5.5 AAA 的 44px）
 *   此前 sm/md 为 28/36px，默认尺寸未达设计契约要求。
 *
 * 所有颜色 / 间距 / 圆角 / 字体均引用 design tokens，禁止散落魔法值。
 *
 * 对比度（2026-09-16 审查 P1 修复）：primary 与 danger 改用语义实底 token，
 * 使「按钮文字 on 按钮底」在两种主题下均 ≥4.5:1。此前 danger 用硬编码
 * `text-white` 配暗色主题的 `--danger(#F87171)`，实测仅 2.77:1。
 */
export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";
export type ButtonSize = "sm" | "md" | "lg";

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** 左侧图标（lucide-react Icon 组件，已应用 size）。 */
  leadingIcon?: React.ReactNode;
  /** 右侧图标。 */
  trailingIcon?: React.ReactNode;
  /** 是否占满父容器宽度。 */
  fullWidth?: boolean;
  /**
   * 加载态：显示 spinner、保留原图标位置、自动禁用点击。
   * 加载中会以 `aria-busy` 标注，并把可见文案替换为 `loadingLabel`（若提供）。
   */
  loading?: boolean;
  /** 加载态下显示的文案；不传则保留 children（仅叠加 spinner）。 */
  loadingLabel?: React.ReactNode;
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary:
    "bg-accent-solid text-accent-on hover:bg-accent-solid-hover active:bg-accent-solid-active focus-visible:shadow-focus",
  secondary:
    "bg-transparent text-accent border border-accent hover:bg-accent-soft focus-visible:shadow-focus",
  danger:
    "bg-danger-solid text-danger-on hover:opacity-90 active:opacity-80 focus-visible:shadow-focus",
  ghost: "bg-surface-2 text-fg hover:bg-accent-soft hover:text-accent focus-visible:shadow-focus",
};

const SIZE_CLASS: Record<ButtonSize, string> = {
  sm: "h-9 px-2.5 text-xs gap-1 rounded-sm",
  md: "h-11 px-3.5 text-sm gap-1.5 rounded-md",
  lg: "h-12 px-5 text-base gap-2 rounded-md",
};

export const Button: React.FC<ButtonProps> = ({
  variant = "primary",
  size = "md",
  leadingIcon,
  trailingIcon,
  fullWidth = false,
  loading = false,
  loadingLabel,
  className = "",
  children,
  disabled,
  ...rest
}) => {
  const classes = [
    "inline-flex items-center justify-center font-medium",
    "transition-colors duration-base ease-standard",
    "focus:outline-none focus-visible:outline-none",
    "disabled:opacity-50 disabled:cursor-not-allowed disabled:pointer-events-none",
    VARIANT_CLASS[variant],
    SIZE_CLASS[size],
    fullWidth ? "w-full" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <button
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <Loader2 size={16} className="animate-spin" aria-hidden="true" /> : leadingIcon}
      {loading && loadingLabel !== undefined ? loadingLabel : children}
      {!loading && trailingIcon}
    </button>
  );
};

export default Button;
