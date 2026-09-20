import React from "react";

/**
 * Loading — Spinner 加载指示器。
 *
 * 引用 design tokens 的 accent 色 + motion；尊重 prefers-reduced-motion
 * （tokens.css 已统一禁用动画）。
 *
 * 2026-09-16 审查清理：同文件内的 `Skeleton` 组件在生产代码中零引用，
 * 且其 `variant` 参数存在死分支（`variant === "rect" ? rounded : rounded`
 * 两分支返回相同值，rect/text 无区别）。已整体删除。
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

export default Loading;
