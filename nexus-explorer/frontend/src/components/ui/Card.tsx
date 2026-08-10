import React from "react";

/**
 * Card — 通用卡片容器。
 *
 * 结构：header（标题 + 可选操作区）/ body / footer。
 * 颜色 / 圆角 / 阴影 / 间距均引用 design tokens。
 */
export interface CardProps {
  /** 卡片标题。 */
  title?: React.ReactNode;
  /** 标题旁的辅助说明（小字 muted）。 */
  subtitle?: React.ReactNode;
  /** 右上角操作区（按钮 / Badge 等）。 */
  actions?: React.ReactNode;
  /** 主内容。 */
  children?: React.ReactNode;
  /** 底部区域。 */
  footer?: React.ReactNode;
  /** 是否可点击（出现 hover 边框 + cursor-pointer）。 */
  interactive?: boolean;
  /** 额外 className（覆盖在最后）。 */
  className?: string;
  /** 内边距紧凑模式（p-3 而非 p-5）。 */
  compact?: boolean;
  /** 点击回调（interactive=true 时生效）。 */
  onClick?: (e: React.MouseEvent<HTMLDivElement>) => void;
}

export const Card: React.FC<CardProps> = ({
  title,
  subtitle,
  actions,
  children,
  footer,
  interactive = false,
  className = "",
  compact = false,
  onClick,
}) => {
  const base = [
    "bg-surface border border-border rounded-lg",
    compact ? "p-3" : "p-5",
    interactive
      ? "cursor-pointer hover:border-accent hover:bg-surface-2 transition-colors duration-base ease-standard"
      : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  const hasHeader = title || actions;

  return (
    <div
      className={base}
      onClick={interactive ? onClick : undefined}
      role={interactive ? "button" : undefined}
      tabIndex={interactive ? 0 : undefined}
    >
      {hasHeader && (
        <div className="flex items-start justify-between mb-3">
          <div>
            {title && (
              <h3 className="text-sm font-semibold text-fg leading-tight">
                {title}
              </h3>
            )}
            {subtitle && (
              <p className="text-xs text-muted mt-0.5">{subtitle}</p>
            )}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </div>
      )}
      {children}
      {footer && (
        <div className="mt-4 pt-3 border-t border-border-soft">{footer}</div>
      )}
    </div>
  );
};

export default Card;