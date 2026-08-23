import React, { useState, useEffect } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { TransactionInfo } from "../types";
import { DetailPageLayout, Badge, type BadgeTone } from "../components/ui";

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
  const { t } = useTranslation();
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
    tx.status === "success"
      ? "success"
      : tx.status === "failed"
        ? "danger"
        : "warning";

  const rows: [string, React.ReactNode][] = [
    [t("tx.txHash"), <code className="break-all text-xs text-fg">{tx.txHash}</code>],
    [
      t("tx.status"),
      <Badge tone={statusTone} solid>
        {tx.status.toUpperCase()}
      </Badge>,
    ],
    [
      t("tx.block"),
      <Link
        to={`/block/${tx.blockHeight}`}
        className="text-accent hover:text-accent-hover hover:underline font-mono"
      >
        #{tx.blockHeight}
      </Link>,
    ],
    [
      t("tx.type"),
      <span className="text-fg-2">{tx.typeName || t("tx.typeFallback", { type: tx.type })}</span>,
    ],
    [
      t("tx.from"),
      <Link
        to={`/address/${tx.from}`}
        className="text-accent hover:text-accent-hover hover:underline break-all text-xs"
      >
        {tx.from}
      </Link>,
    ],
    [
      t("tx.to"),
      <Link
        to={`/address/${tx.to}`}
        className="text-accent hover:text-accent-hover hover:underline break-all text-xs"
      >
        {tx.to}
      </Link>,
    ],
    [
      t("tx.amount"),
      <span className="text-success font-medium">{tx.amount} NEX</span>,
    ],
    [t("tx.fee"), <span className="text-fg-2">{tx.fee} NEX</span>],
    [t("tx.nonce"), <span className="font-mono text-fg-2">{tx.nonce}</span>],
    [t("tx.timestamp"), new Date(tx.timestamp * 1000).toLocaleString()],
  ];

  return (
    <DetailPageLayout title={t("tx.title")} backLabel={t("tx.back")}>
      <div className="bg-surface border border-border rounded-lg divide-y divide-border-soft">
        {rows.map(([label, value]) => (
          <div
            key={label}
            className="px-5 py-3 grid grid-cols-4 gap-2 text-sm"
          >
            <span className="text-muted">{label}</span>
            <span className="col-span-3 text-fg">{value}</span>
          </div>
        ))}
      </div>
    </DetailPageLayout>
  );
};

export default TxDetailPage;
