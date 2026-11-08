import React from "react";
import { AlertCircle, RefreshCw } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "./Button";

/**
 * ErrorState — 页面级取数失败的行内错误态（2026-11-07 生产就绪度审计修复，发现 B7）。
 *
 * <h3>为什么需要它</h3>
 * <p>审计实测：首页在链节点不可达时渲染出的内容只有「标题 + 一句原始英文错误」，
 * <b>整页 0 个按钮</b> —— 用户没有任何重试或离开的出口。仓里其实早有重试原语
 * （{@code ErrorBoundary} 带 `common.retry` 按钮），但**只有渲染崩溃那一层有**，
 * 页面级取数失败这一层没有；而 `EmptyState`/`Skeleton` 已在 2026-09-16 的清理中
 * 被删除（`components/ui/index.ts` 自述"零引用"），于是各页各写一份或干脆不写。</p>
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>{@code message} 必须是**已经过 i18n** 的用户文案 —— 用
 *       {@code utils/displayError.ts#displayErrorMessage} 产出，不要把
 *       {@code err.message} 直接塞进来（那正是 B5）。</li>
 *   <li>{@code onRetry} <b>不传就不渲染按钮</b>：没有可重入的加载函数时，
 *       不要给一个点了没反应的假按钮。</li>
 *   <li>样式照抄既有 danger banner 的类名（`bg-surface-2` + `border-danger/30` +
 *       `text-danger`），与同仓其它页保持同一口径。</li>
 * </ul>
 */
export interface ErrorStateProps {
  /** 已 i18n 的用户文案（见 utils/displayError.ts）。 */
  message: string;
  /** 重试回调；不传则不渲染重试按钮。 */
  onRetry?: () => void;
  /** 附加说明（可选，已 i18n），如"请确认后端已启动"。 */
  hint?: React.ReactNode;
  className?: string;
}

export const ErrorState: React.FC<ErrorStateProps> = ({
  message,
  onRetry,
  hint,
  className = "",
}) => {
  const { t } = useTranslation();

  return (
    <div
      role="alert"
      className={[
        "flex gap-2 text-sm text-danger bg-surface-2 border border-danger/30 rounded-md px-4 py-3",
        onRetry ? "items-center justify-between gap-3" : "items-start",
        className,
      ]
        .filter(Boolean)
        .join(" ")}
    >
      <span className="flex items-start gap-2 min-w-0">
        <AlertCircle size={16} aria-hidden="true" className="mt-0.5 shrink-0" />
        <span className="min-w-0 space-y-1">
          <span className="block break-words">{message}</span>
          {hint ? <span className="block text-xs text-muted break-words">{hint}</span> : null}
        </span>
      </span>

      {onRetry ? (
        <Button
          variant="secondary"
          size="sm"
          onClick={onRetry}
          leadingIcon={<RefreshCw size={14} aria-hidden="true" />}
          className="shrink-0"
        >
          {t("common.retry")}
        </Button>
      ) : null}
    </div>
  );
};

export default ErrorState;
