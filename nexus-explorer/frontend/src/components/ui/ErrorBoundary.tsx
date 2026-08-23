import React from "react";
import { AlertTriangle, RefreshCw } from "lucide-react";
import i18n from "../../i18n";

/**
 * ErrorBoundary — React 错误边界。
 *
 * 捕获子树渲染期 / 生命周期内的同步错误，展示统一 fallback UI，
 * 避免整页白屏。异步错误（fetch / event handler）需调用方自行 try/catch。
 *
 * P2: 硬编码中文文案提取到 i18n（common.pageError / common.unknownError / common.retry）。
 *     ErrorBoundary 是 class component，无法使用 useTranslation hook，
 *     改用 i18n.t 直接读取当前语言文案。
 */
export interface ErrorBoundaryProps {
  children?: React.ReactNode;
  /** 自定义 fallback 渲染函数（接收 error + reset）。 */
  fallback?: (error: Error, reset: () => void) => React.ReactNode;
  /** 错误回调（埋点 / 上报）。 */
  onError?: (error: Error, info: React.ErrorInfo) => void;
}

interface ErrorBoundaryState {
  error: Error | null;
}

export class ErrorBoundary extends React.Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    // eslint-disable-next-line no-console
    console.error("[ErrorBoundary]", error, info);
    this.props.onError?.(error, info);
  }

  reset = (): void => this.setState({ error: null });

  render(): React.ReactNode {
    const { error } = this.state;
    const { children, fallback } = this.props;
    if (!error) return children;

    if (fallback) return fallback(error, this.reset);

    // 默认 fallback：警告图标 + 消息 + 重试按钮
    return (
      <div
        className="flex flex-col items-center justify-center py-12 px-4 text-center"
        role="alert"
      >
        <AlertTriangle
          size={32}
          strokeWidth={1.5}
          className="mb-3 text-warn opacity-80"
        />
        <p className="text-sm font-medium text-fg mb-1">
          {i18n.t("common.pageError")}
        </p>
        <p className="text-xs text-muted mb-4 max-w-md break-all">
          {error.message || i18n.t("common.unknownError")}
        </p>
        <button
          type="button"
          onClick={this.reset}
          aria-label={i18n.t("common.retry")}
          className="inline-flex items-center gap-1.5 h-8 px-3 text-xs font-medium bg-surface border border-border rounded-md text-fg-2 hover:border-accent hover:text-accent transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
        >
          <RefreshCw size={14} />
          {i18n.t("common.retry")}
        </button>
      </div>
    );
  }
}

export default ErrorBoundary;
