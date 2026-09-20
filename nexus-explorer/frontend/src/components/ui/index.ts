/**
 * 共享 UI 组件 barrel 导出。
 * 集中暴露 Button / Card / Modal / Loading / ErrorBoundary / Badge / DetailPageLayout，
 * 页面通过 `import { Button, Card } from "../components/ui"` 引用。
 *
 * 2026-09-16 审查清理：移除 `Table` 与 `Skeleton` 的导出 —— 两者在生产代码中
 * 零引用（Table 140 行、Skeleton 20 行），Table 还带有 `overflow-hidden` 而非
 * `overflow-x-auto` 的窄屏裁剪问题。如需表格/骨架屏，请重新引入并补测试。
 */
export { Button } from "./Button";
export type { ButtonProps, ButtonVariant, ButtonSize } from "./Button";

export { Card } from "./Card";
export type { CardProps } from "./Card";

export { Modal } from "./Modal";
export type { ModalProps } from "./Modal";

export { Loading } from "./Loading";
export type { LoadingProps } from "./Loading";

export { ErrorBoundary } from "./ErrorBoundary";
export type { ErrorBoundaryProps } from "./ErrorBoundary";

export { Badge } from "./Badge";
export type { BadgeProps, BadgeTone } from "./Badge";

export { DetailPageLayout } from "./DetailPageLayout";
export type { DetailPageLayoutProps } from "./DetailPageLayout";
