import React from "react";

/**
 * Loading — Spinner 加载指示器。
 *
 * 引用 design tokens 的 accent 色 + motion；尊重 prefers-reduced-motion
 * （tokens.css 已统一禁用动画）。
 */
export interface LoadingProps {
  /** 尺寸（像素）。 */
  size?: number;
  /** 描边宽度。 */
  strokeWidth?: number;
  /** 旁挂文案。 */
  label?: React.ReactNode;
  /** 额外 className。 */
  className?: string;
}

export const Loading: React.FC<LoadingProps> = ({
  size = 20,
  strokeWidth = 2,
  label,
  className = "",
}) => (
  <div
    className={`inline-flex items-center gap-2 text-muted ${className}`}
    role="status"
    aria-live="polite"
  >
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      className="animate-spin text-accent"
      aria-hidden="true"
    >
      <circle
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        strokeOpacity={0.25}
      />
      <path
        d="M22 12a10 10 0 0 1-10 10"
        stroke="currentColor"
        strokeWidth={strokeWidth}
        strokeLinecap="round"
      />
    </svg>
    {label && <span className="text-sm">{label}</span>}
  </div>
);

/**
 * Skeleton — 骨架屏占位。
 *
 * 用于表格 / 卡片 / 详情页首屏加载，避免空白闪烁。
 */
export interface SkeletonProps {
  /** 形状。 */
  variant?: "text" | "rect" | "circle";
  /** 宽度 Tailwind 类（默认 w-full）。 */
  width?: string;
  /** 高度 Tailwind 类（默认 h-4）。 */
  height?: string;
  /** 圆角（仅 rect 生效，默认 rounded-sm）。 */
  rounded?: string;
  /** 额外 className。 */
  className?: string;
}

export const Skeleton: React.FC<SkeletonProps> = ({
  variant = "text",
  width = "w-full",
  height = "h-4",
  rounded = "rounded-sm",
  className = "",
}) => {
  const shape =
    variant === "circle"
      ? "rounded-full"
      : variant === "rect"
        ? rounded
        : rounded;
  return (
    <div
      className={`${width} ${height} ${shape} bg-surface-2 animate-pulse ${className}`}
      aria-hidden="true"
    />
  );
};

export default Loading;