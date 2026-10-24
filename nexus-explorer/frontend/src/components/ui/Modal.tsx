import React, { useEffect, useId, useRef } from "react";
import { X } from "lucide-react";
import { useTranslation } from "react-i18next";

/**
 * Modal — 通用弹窗。
 *
 * 特性：
 *   - 遮罩（半透明黑底，点击关闭）
 *   - ESC 键关闭（始终可用）
 *   - 标题 + 关闭按钮 + 内容 + 底部确认/取消操作区
 *   - body 滚动锁定
 *   - 焦点陷阱 + 关闭后焦点归还
 *   - 焦点环 / 阴影 / 圆角引用 design tokens
 *
 * 2026-09-16 审查修复：
 *  1. `disableBackdropClose` 此前同时禁用了 ESC —— 该 prop 的语义只是「禁用遮罩
 *     点击关闭」，与键盘退出是两个独立通道。合并后强确认场景下键盘用户无法退出
 *     对话框，违反 WCAG 2.1.2（No Keyboard Trap）。现 ESC 始终可关闭。
 *  2. 补焦点管理：打开时把焦点移入对话框、Tab 循环限制在对话框内、关闭后归还
 *     到触发元素。此前无任何焦点管理，键盘用户 Tab 会穿透到背景内容。
 *  3. `aria-labelledby` 的 id 此前硬编码 `modal-title`，同时打开两个 Modal 会产生
 *     重复 id。改用 `useId()` 生成唯一 id。
 */

/** 可聚焦元素选择器（用于焦点陷阱）。 */
const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "textarea:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  '[tabindex]:not([tabindex="-1"])',
].join(",");

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
  /** 是否禁用**遮罩点击**关闭（用于强确认场景）。不影响 ESC 关闭。 */
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
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);

  /**
   * `onClose` 的最新引用。
   *
   * 缺陷修复（2026-10-24）：`onClose` **不能进下面那个 effect 的依赖数组**。
   * 父组件几乎总是传内联箭头函数（`onClose={() => setX(false)}`），每次渲染
   * 都是新引用；一旦它成为依赖，弹窗打开期间父组件的**任何** state 变化
   * （例如用户在弹窗里输入）都会让 effect 清理并重跑 —— 而清理里恰好有
   * `previouslyFocused?.focus()`，于是焦点被从输入框抢回到触发元素，
   * 用户第二个字符起就输入不进任何东西。
   *
   * 实证：提现审批台的「拒绝原因」文本框，`user.type` 只写进 1/4 个字符。
   * 用 ref 持有最新回调，effect 只依赖 `open` / `disableBackdropClose`
   * （两者都是稳定值），焦点管理遂与父组件渲染解耦。
   */
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  // ESC 关闭 + body 滚动锁定 + 焦点陷阱 + 焦点归还
  // 依赖只含 open / disableBackdropClose：见上面 onCloseRef 的说明。
  useEffect(() => {
    if (!open) return;

    const dialog = dialogRef.current;
    const previouslyFocused =
      typeof document !== "undefined" ? (document.activeElement as HTMLElement | null) : null;

    // 把焦点移入对话框：优先首个可聚焦元素，否则对话框本身（tabIndex=-1）
    const focusables = dialog
      ? Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      : [];
    (focusables[0] ?? dialog)?.focus();

    const onKey = (e: KeyboardEvent) => {
      // ESC 始终可关闭（与 disableBackdropClose 无关，见文件头说明）
      if (e.key === "Escape") {
        onCloseRef.current();
        return;
      }
      if (e.key !== "Tab" || !dialog) return;

      const items = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
      if (items.length === 0) {
        e.preventDefault();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;

      if (e.shiftKey && (active === first || active === dialog)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
      // 焦点归还到触发元素
      previouslyFocused?.focus?.();
    };
  }, [open, disableBackdropClose]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-modal flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={title ? titleId : undefined}
    >
      {/* 遮罩 */}
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={disableBackdropClose ? undefined : onClose}
        aria-hidden="true"
      />
      {/* 弹窗主体 */}
      <div
        ref={dialogRef}
        tabIndex={-1}
        className={`relative w-full ${maxWidth} bg-surface border border-border rounded-lg shadow-raised focus:outline-none`}
      >
        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border-soft">
          {title && (
            <h2 id={titleId} className="text-sm font-semibold text-fg">
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
