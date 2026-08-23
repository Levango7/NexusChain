import React, { useEffect } from "react";
import { X } from "lucide-react";
import { useTranslation } from "react-i18next";

/**
 * Modal — 通用弹窗。
 *
 * 特性：
 *   - 遮罩（半透明黑底，点击关闭）
 *   - ESC 键关闭
 *   - 标题 + 关闭按钮 + 内容 + 底部确认/取消操作区
 *   - body 滚动锁定
 *   - 焦点环 / 阴影 / 圆角引用 design tokens
 *
 * P2: 关闭按钮 aria-label 硬编码中文提取到 i18n（common.closeDialog）。
 */
export interface ModalProps {
  /** 是否打开。 */
  open: boolean;
  /** 关闭回调（点击遮罩 / ESC / 关闭按钮触发）。 */
  onClose: () => void;
  /** 标题。 */
  title?: React.ReactNode;
  /** 主内容。 */
  children?: React.ReactNode;
  /** 底部操作区（确认/取消按钮等）。 */
  footer?: React.ReactNode;
  /** 弹窗最大宽度（Tailwind 类，默认 max-w-md）。 */
  maxWidth?: string;
  /** 是否禁用遮罩点击关闭（用于强确认场景）。 */
  disableBackdropClose?: boolean;
}

export const Modal: React.FC<ModalProps> = ({
  open,
  onClose,
  title,
  children,
  footer,
  maxWidth = "max-w-md",
  disableBackdropClose = false,
}) => {
  const { t } = useTranslation();
  // ESC 关闭 + body 滚动锁定
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !disableBackdropClose) onClose();
    };
    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [open, onClose, disableBackdropClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-modal flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={title ? "modal-title" : undefined}
    >
      {/* 遮罩 */}
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={disableBackdropClose ? undefined : onClose}
        aria-hidden="true"
      />
      {/* 弹窗主体 */}
      <div
        className={`relative w-full ${maxWidth} bg-surface border border-border rounded-lg shadow-raised`}
      >
        {(title || true) && (
          <div className="flex items-center justify-between px-5 py-3.5 border-b border-border-soft">
            {title && (
              <h2
                id="modal-title"
                className="text-sm font-semibold text-fg"
              >
                {title}
              </h2>
            )}
            <button
              type="button"
              onClick={onClose}
              aria-label={t("common.closeDialog")}
              className="ml-auto -mr-1 p-1 text-muted hover:text-fg hover:bg-accent-soft rounded-sm transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
            >
              <X size={16} />
            </button>
          </div>
        )}
        <div className="px-5 py-4">{children}</div>
        {footer && (
          <div className="px-5 py-3.5 border-t border-border-soft flex items-center justify-end gap-2">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
};

export default Modal;