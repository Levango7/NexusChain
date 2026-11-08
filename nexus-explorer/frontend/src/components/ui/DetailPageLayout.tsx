import React from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { Loading } from "./Loading";
import { ErrorState } from "./ErrorState";
import { PageHeader } from "../layout/PageHeader";

/**
 * DetailPageLayout — 详情页通用布局骨架。
 *
 * 提取自 BlockDetailPage / TxDetailPage / AddressPage 三页共享的结构：
 *   - 顶部 header（返回首页链接 + 标题）
 *   - 全屏 loading 占位
 *   - 全屏 error 占位
 *   - main 内容容器（max-w-4xl）
 *
 * 设计契约：
 *   - 颜色 / 间距 / 圆角 / 字体均引用 design tokens
 *   - 返回图标统一 lucide-react <ArrowLeft />
 *   - Loading 文案统一 <Loading /> 组件
 */
export interface DetailPageLayoutProps {
  /** 标题节点（渲染于 header 右侧）。 */
  title?: React.ReactNode;
  /** 返回链接文案（紧随 ArrowLeft 图标）。 */
  backLabel?: React.ReactNode;
  /** Loading 文案（loading=true 时全屏展示）。 */
  loadingLabel?: React.ReactNode;
  /** 是否处于加载态。 */
  loading?: boolean;
  /** 错误文案（非空时全屏展示，优先级高于 loading）。 */
  error?: string | null;
  /**
   * 取数失败时的重试回调（2026-11-07 审计修复 B7）；不传则不渲染重试按钮。
   *
   * <p>调用方通常用 `setReloadKey(k => k + 1)` 驱动其 useEffect 重跑。</p>
   */
  onRetry?: () => void;
  /** 主内容节点。 */
  children?: React.ReactNode;
  /** main 容器额外 className（默认 max-w-4xl + py-6）。 */
  mainClassName?: string;
}

/**
 * 详情页布局骨架。loading / error 任一命中时全屏占位并隐藏 children，
 * 否则渲染 header + main(children)。
 */
export const DetailPageLayout: React.FC<DetailPageLayoutProps> = ({
  title,
  backLabel,
  loadingLabel,
  loading = false,
  error = null,
  onRetry,
  children,
  mainClassName = "max-w-4xl mx-auto px-4 py-6",
}) => {
  if (loading) {
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <Loading label={loadingLabel} />
      </div>
    );
  }

  const header = (
    <PageHeader maxWidth="max-w-4xl" innerClassName="gap-4">
      <Link
        to="/"
        className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
      >
        <ArrowLeft size={14} />
        {backLabel}
      </Link>
      {title && <h1 className="text-sm font-semibold text-fg-2">{title}</h1>}
    </PageHeader>
  );

  if (error) {
    // 2026-11-07 审计修复（B7/B10 同族）：此前错误分支是
    // `<div className="… flex items-center justify-center text-danger">{error}</div>`
    // —— 只有一行红字：**连 header 与「返回」一起消失**，用户除浏览器后退外无出口，
    // 且没有重试入口。现改为"保留页面外壳 + 行内 ErrorState（含可选重试）"。
    return (
      <div className="min-h-screen bg-bg text-fg">
        {header}
        <main className={mainClassName}>
          <ErrorState message={error} onRetry={onRetry} />
        </main>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-bg text-fg">
      {header}
      <main className={mainClassName}>{children}</main>
    </div>
  );
};

export default DetailPageLayout;
