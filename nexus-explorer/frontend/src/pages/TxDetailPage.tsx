import React, { useState, useEffect } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { TransactionInfo } from "../types";
import { DetailPageLayout, Badge, type BadgeTone } from "../components/ui";
import { orDash } from "../utils/value";
import { formatAbsoluteTime } from "../utils/time";

/**
 * TxDetailPage — 交易详情页。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens
 *   - 状态色（text-emerald-400 / text-red-400 / text-yellow-400）替换为 Badge tone
 *   - &larr; 字符替换为 lucide-react <ArrowLeft />（经 DetailPageLayout 统一）
 *   - Loading 文案替换为 <Loading /> 组件
 *   - P1: 复用 DetailPageLayout 提取的 header + loading + error 骨架
 */
const TxDetailPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { hash } = useParams<{ hash: string }>();
  const [tx, setTx] = useState<TransactionInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // hash 为 undefined 时 api.getTransaction(hash!) 会崩溃
    if (!hash) return;
    (async () => {
      try {
        const data = await api.getTransaction(hash);
        setTx(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : t("tx.notFound"));
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hash]);

  if (loading || error || !tx) {
    return (
      <DetailPageLayout
        loading={loading}
        loadingLabel={t("tx.loading")}
        error={error}
        title={t("tx.title")}
        backLabel={t("tx.back")}
      />
    );
  }

  const statusTone: BadgeTone =
    tx.status === "success" ? "success" : tx.status === "failed" ? "danger" : "warning";

  // 字段与后端 toRpcTransaction 严格对齐（2026-09-16 审查 P0/P1 修复）：
  // 已移除 type / typeName / fee / nonce —— core 的 JSON-RPC 桥接不返回这些字段。
  // 此前 `{tx.fee} NEX` 因 React 把 undefined 渲染为空串，页面显示为
  // 「手续费 NEX」（数值空白），用户无法区分「手续费为 0」与「字段缺失」。
  const rows: [string, React.ReactNode][] = [
    [t("tx.txHash"), <code className="break-all text-xs text-fg">{orDash(tx.txHash)}</code>],
    [
      t("tx.status"),
      <Badge tone={statusTone} outlined>
        {String(orDash(tx.status)).toUpperCase()}
      </Badge>,
    ],
    [
      t("tx.block"),
      tx.blockHeight != null ? (
        <Link
          to={`/block/${tx.blockHeight}`}
          className="text-accent hover:text-accent-hover hover:underline font-mono"
        >
          #{tx.blockHeight}
        </Link>
      ) : (
        "—"
      ),
    ],
    [
      t("tx.from"),
      <Link
        to={`/address/${tx.from}`}
        className="text-accent hover:text-accent-hover hover:underline break-all text-xs"
      >
        {orDash(tx.from)}
      </Link>,
    ],
    [
      t("tx.to"),
      <Link
        to={`/address/${tx.to}`}
        className="text-accent hover:text-accent-hover hover:underline break-all text-xs"
      >
        {orDash(tx.to)}
      </Link>,
    ],
    [t("tx.amount"), <span className="text-success font-medium">{orDash(tx.amount)} NEX</span>],
    [t("tx.timestamp"), formatAbsoluteTime(tx.timestamp, i18n.language)],
  ];

  return (
    <DetailPageLayout title={t("tx.title")} backLabel={t("tx.back")}>
      <div className="bg-surface border border-border rounded-lg divide-y divide-border-soft">
        {rows.map(([label, value]) => (
          // 响应式（2026-09-16 审查 P1）：窄屏单列堆叠，≥sm 起恢复 4 列布局
          <div
            key={label}
            className="px-5 py-3 grid grid-cols-1 gap-1 text-sm sm:grid-cols-4 sm:gap-2"
          >
            <span className="text-muted">{label}</span>
            <span className="text-fg sm:col-span-3">{value}</span>
          </div>
        ))}
      </div>
    </DetailPageLayout>
  );
};

export default TxDetailPage;
