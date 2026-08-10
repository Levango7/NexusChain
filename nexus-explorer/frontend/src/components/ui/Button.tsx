import React from "react";

/**
 * Button — 通用按钮组件。
 *
 * 变体（variant）：
 *   - primary  ：主操作（accent 实底 + 白字）
 *   - secondary：次操作（surface 底 + border）
 *   - danger   ：破坏性操作（danger 实底）
 *   - ghost    ：幽灵按钮（透明底，hover 出 soft 背景）
 *
 * 尺寸（size）：sm / md / lg
 *
 * 所有颜色 / 间距 / 圆角 / 字体均引用 design tokens，禁止散落魔法值。
 */
export type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";
export type ButtonSize = "sm" | "md" | "lg";

export interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** 左侧图标（lucide-react Icon 组件，已应用 size）。 */
  leadingIcon?: React.ReactNode;
  /** 右侧图标。 */
  trailingIcon?: React.ReactNode;
  /** 是否占满父容器宽度。 */
  fullWidth?: boolean;
}

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary:
    "bg-accent text-accent-on hover:bg-accent-hover active:bg-accent-active focus-visible:shadow-focus",
  secondary:
    "bg-surface text-fg border border-border hover:border-accent hover:text-accent focus-visible:shadow-focus",
  danger:
    "bg-danger text-white hover:opacity-90 active:opacity-80 focus-visible:shadow-focus",
  ghost:
    "bg-transparent text-fg-2 hover:bg-accent-soft hover:text-accent focus-visible:shadow-focus",
};

const SIZE_CLASS: Record<ButtonSize, string> = {
  sm: "h-7 px-2.5 text-xs gap-1 rounded-sm",
  md: "h-9 px-3.5 text-sm gap-1.5 rounded-md",
  lg: "h-11 px-5 text-base gap-2 rounded-md",
};

export const Button: React.FC<ButtonProps> = ({
  variant = "primary",
  size = "md",
  leadingIcon,
  trailingIcon,
  fullWidth = false,
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
    <button className={classes} disabled={disabled} {...rest}>
      {leadingIcon}
      {children}
      {trailingIcon}
    </button>
  );
};

export default Button;