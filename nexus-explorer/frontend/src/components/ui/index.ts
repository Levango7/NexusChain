/**
 * 共享 UI 组件 barrel 导出。
 * 集中暴露 Button / Card / Modal / Table / Loading / ErrorBoundary，
 * 页面通过 `import { Button, Card } from "@/components/ui"` 引用。
 */
export { Button } from "./Button";
export type { ButtonProps, ButtonVariant, ButtonSize } from "./Button";

export { Card } from "./Card";
export type { CardProps } from "./Card";

export { Modal } from "./Modal";
export type { ModalProps } from "./Modal";

export { Table } from "./Table";
export type { TableProps, Column, TableRow } from "./Table";

export { Loading, Skeleton } from "./Loading";
export type { LoadingProps, SkeletonProps } from "./Loading";

export { ErrorBoundary } from "./ErrorBoundary";
export type { ErrorBoundaryProps } from "./ErrorBoundary";

export { Badge } from "./Badge";
export type { BadgeProps, BadgeTone } from "./Badge";

export { DetailPageLayout } from "./DetailPageLayout";
export type { DetailPageLayoutProps } from "./DetailPageLayout";