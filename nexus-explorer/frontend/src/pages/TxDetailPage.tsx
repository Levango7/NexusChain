import React, { useState, useEffect } from "react";
import { useParams, Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { TransactionInfo } from "../types";
import { DetailPageLayout, Badge, type BadgeTone } from "../components/ui";
import { displayErrorMessage } from "../utils/displayError";
import { orDash } from "../utils/value";
import { formatAbsoluteTime } from "../utils/time";
import { parseBatchTransferItems } from "../utils/batchTransfer";

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
  /** 重试计数：自增即重跑加载（审计 B7）。 */
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // hash 为 undefined 时 api.getTransaction(hash!) 会崩溃
    if (!hash) return;
    setLoading(true);
    (async () => {
      try {
        const data = await api.getTransaction(hash);
        setTx(data);
        // 200 + null 表示「查无此交易」：显式置错误态，避免三态全空只剩 header
        setError(data ? null : t("tx.notFound"));
      } catch (err) {
        console.error("API请求失败:", err);
        setTx(null);
        setError(displayErrorMessage(err, t, "tx.notFound"));
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hash, reloadKey]);

  if (loading || error || !tx) {
    return (
      <DetailPageLayout
        loading={loading}
        loadingLabel={t("tx.loading")}
        error={error}
        onRetry={() => setReloadKey((k) => k + 1)}
        title={t("tx.title")}
        backLabel={t("tx.back")}
      />
    );
  }

  const statusTone: BadgeTone =
    tx.status === "success" ? "success" : tx.status === "failed" ? "danger" : "warning";
  const batchItems = tx.typeName === "BATCH_TRANSFER" ? parseBatchTransferItems(tx.data) : [];

  // 字段与后端 toRpcTransaction 严格对齐：type/typeName/fee/nonce 只作为
  // BATCH_TRANSFER 的可选增量出现；旧交易缺失时仍不渲染空标签。
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
    ...(tx.typeName ? [[t("tx.type"), tx.typeName] as [string, React.ReactNode]] : []),
    ...(tx.fee !== undefined ? [[t("tx.fee"), `${tx.fee} NEX`] as [string, React.ReactNode]] : []),
    ...(tx.nonce !== undefined ? [[t("tx.nonce"), tx.nonce] as [string, React.ReactNode]] : []),
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
      {batchItems.length > 0 && (
        <section className="mt-4 bg-surface border border-border rounded-lg px-5 py-4">
          <h2 className="text-sm font-medium text-fg mb-3">{t("tx.recipients")}</h2>
          <ul className="divide-y divide-border-soft">
            {batchItems.map((item, index) => (
              <li
                key={`${index}-${item.address}`}
                className="py-2 flex flex-col gap-1 sm:flex-row sm:justify-between"
              >
                <code className="break-all text-xs text-fg">{item.address}</code>
                <span className="text-success font-medium">{item.amount} NEX</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </DetailPageLayout>
  );
};

export default TxDetailPage;
