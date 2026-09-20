import React from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";

/**
 * PageHeader — 共享页头骨架。
 *
 * 背景（2026-09-16 审查 P1）：DESIGN.md §5 要求「抽 Layout + Header … 消除孤立路由
 * 与双套页头」，但实现中 HomePage / OrchestrationDashboard / Settings /
 * DetailPageLayout **各自手写了一套页头**（同样的 `border-b border-border
 * bg-surface/80 backdrop-blur sticky top-0 z-sticky` + `h-14` 容器）。
 * 本组件把该骨架收敛为一处，避免后续样式改动需要在 4 个文件同步。
 *
 * 只收敛「外层结构」，内部横向布局由调用方通过 `innerClassName` 控制
 * （例如首页需要 `justify-between`，详情页不需要）。
 */
export interface PageHeaderProps {
  children?: React.ReactNode;
  /** 内容容器最大宽度类，默认 `max-w-6xl`（首页/编排页）；详情页传 `max-w-4xl`。 */
  maxWidth?: string;
  /** 内层 flex 容器的额外类（如 `justify-between`）。 */
  innerClassName?: string;
  className?: string;
}

export const PageHeader: React.FC<PageHeaderProps> = ({
  children,
  maxWidth = "max-w-6xl",
  innerClassName = "",
  className = "",
}) => (
  <header
    className={`border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky ${className}`}
  >
    <div className={`${maxWidth} mx-auto px-4 h-14 flex items-center gap-4 ${innerClassName}`}>
      {children}
    </div>
  </header>
);

/**
 * BackLink — 页头内的返回链接（统一图标、颜色与焦点环）。
 *
 * 此前 Settings / DetailPageLayout / OrchestrationDashboard 各写一份，
 * 且颜色类略有出入。
 */
export interface BackLinkProps {
  /** 目标路径，默认 `/`（首页）。 */
  to?: string;
  children: React.ReactNode;
  className?: string;
}

export const BackLink: React.FC<BackLinkProps> = ({ to = "/", children, className = "" }) => (
  <Link
    to={to}
    className={`flex items-center gap-1 text-accent hover:text-accent-hover text-sm shrink-0 transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus ${className}`}
  >
    <ArrowLeft size={14} aria-hidden="true" />
    {children}
  </Link>
);

export default PageHeader;
