import React, { useState, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { AlertCircle, ArrowLeftRight, RefreshCw } from "lucide-react";
import { api } from "../api/client";
import type { CrossChainTransfer } from "../types";
import { Badge, Button, Loading, type BadgeTone } from "../components/ui";
import { PageHeader, BackLink } from "../components/layout/PageHeader";
import { orDash } from "../utils/value";
import { formatRelativeTime } from "../utils/time";

/** 状态 → Badge 语义色。未知状态回落到 neutral（不抛错、不渲染 undefined）。 */
const STATUS_TONE: Record<CrossChainTransfer["status"], BadgeTone> = {
  pending: "warning",
  confirmed: "success",
  failed: "danger",
};

/** 筛选器取值：空串表示「全部」（不向 BFF 传 status 参数）。 */
const FILTERS: Array<{ value: "" | CrossChainTransfer["status"]; labelKey: string }> = [
  { value: "", labelKey: "crosschain.filterAll" },
  { value: "pending", labelKey: "crosschain.status.pending" },
  { value: "confirmed", labelKey: "crosschain.status.confirmed" },
  { value: "failed", labelKey: "crosschain.status.failed" },
];

/**
 * CrossChainPage — 跨链交易追踪（`/crosschain`）。
 *
 * 背景：与合约页同源 —— README 承诺「跨链交易追踪」，BFF
 * `GET /api/crosschain?limit&status` 一直可用，但前端零消费。
 *
 * 数据契约：字段来自 nexus-explorer/backend/src/rpc.ts#RpcCrossChainTx
 * （core `nexus_getCrossChainTransactions` 透传）。`txId` 是**跨链**交易 ID，
 * 不是链上交易哈希，故行内不提供跳转（避免把用户导向查不到的详情页）。
 */
const CrossChainPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const [transfers, setTransfers] = useState<CrossChainTransfer[]>([]);
  const [status, setStatus] = useState<"" | CrossChainTransfer["status"]>("");
  // 手动刷新信号：自增即触发重新拉取（避免把 refresh 做成第二套取数逻辑）
  const [refreshSeq, setRefreshSeq] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // 竞态守卫：快速切换筛选时，先发出的慢响应可能后到并覆盖当前筛选的结果
    let cancelled = false;
    (async () => {
      setLoading(true);
      try {
        const list = await api.getCrossChain(50, status || undefined);
        if (cancelled) return;
        setTransfers(list);
        setError(null);
      } catch (err) {
        if (cancelled) return;
        console.error("API请求失败:", err);
        setTransfers([]);
        setError(err instanceof Error ? err.message : t("crosschain.loadFailed"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, refreshSeq]);

  return (
    <div className="min-h-screen bg-bg text-fg">
      <PageHeader maxWidth="max-w-6xl" innerClassName="justify-between gap-4">
        <div className="flex items-center gap-3 min-w-0">
          <BackLink>{t("crosschain.back")}</BackLink>
          <span className="text-xs text-muted font-mono truncate hidden sm:inline">
            {t("crosschain.title")}
          </span>
        </div>
        <Button variant="ghost" size="sm" onClick={() => setRefreshSeq((n) => n + 1)}>
          <RefreshCw size={14} aria-hidden="true" />
          {t("common.refresh")}
        </Button>
      </PageHeader>

      <main className="max-w-6xl mx-auto px-4 py-6">
        {/* 状态筛选：显式 aria-pressed —— 颜色/样式不承载状态语义 */}
        <div
          role="group"
          aria-label={t("crosschain.filterLabel")}
          className="flex items-center gap-2 mb-4 flex-wrap"
        >
          <ArrowLeftRight size={14} className="text-muted" aria-hidden="true" />
          {FILTERS.map((f) => (
            <button
              key={f.value || "all"}
              type="button"
              aria-pressed={status === f.value}
              onClick={() => setStatus(f.value)}
              className={`text-xs px-3 py-1 rounded-pill border transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus ${
                status === f.value
                  ? "border-accent text-accent bg-accent-soft"
                  : "border-border text-muted hover:text-accent hover:border-accent"
              }`}
            >
              {t(f.labelKey)}
            </button>
          ))}
        </div>

        {loading && (
          <div className="py-12 flex justify-center">
            <Loading label={t("crosschain.loading")} />
          </div>
        )}

        {!loading && error && (
          <div
            role="alert"
            className="flex items-center gap-2 text-sm text-danger bg-surface-2 border border-danger/30 rounded-md px-4 py-3"
          >
            <AlertCircle size={16} aria-hidden="true" />
            <span>{error}</span>
          </div>
        )}

        {!loading && !error && transfers.length === 0 && (
          <div className="text-center text-sm text-muted py-12">{t("crosschain.empty")}</div>
        )}

        {!loading && !error && transfers.length > 0 && (
          <div className="space-y-3">
            {transfers.map((tx) => (
              <div key={tx.txId} className="bg-surface border border-border rounded-lg px-4 py-3">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-accent font-mono text-sm truncate">{orDash(tx.txId)}</span>
                  <Badge tone={STATUS_TONE[tx.status] ?? "neutral"}>
                    {t(`crosschain.status.${tx.status}`, { defaultValue: tx.status })}
                  </Badge>
                </div>
                <div className="flex items-center justify-between gap-3 mt-2">
                  <span className="text-xs text-fg-2">
                    {orDash(tx.sourceChain)} → {orDash(tx.targetChain)}
                  </span>
                  <span className="text-xs text-muted shrink-0">
                    {formatRelativeTime(tx.timestamp, i18n.language)}
                  </span>
                </div>
                <div className="flex items-center justify-between gap-3 mt-1">
                  <span className="text-xs text-muted truncate">
                    {orDash(tx.from)} → {orDash(tx.to)}
                  </span>
                  <span className="text-xs font-medium text-success shrink-0">
                    {orDash(tx.amount)} NEX
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
};

export default CrossChainPage;
