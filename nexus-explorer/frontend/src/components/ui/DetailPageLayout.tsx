import React from "react";
import { Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { Loading } from "./Loading";

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
  if (error) {
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center text-danger">
        {error}
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link
            to="/"
            className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
          >
            <ArrowLeft size={14} />
            {backLabel}
          </Link>
          {title && <h1 className="text-sm font-semibold text-fg-2">{title}</h1>}
        </div>
      </header>
      <main className={mainClassName}>{children}</main>
    </div>
  );
};

export default DetailPageLayout;
