import React, { useState, useEffect, useCallback, useRef } from "react";
import { Link } from "react-router-dom";
import { AlertTriangle, ShieldAlert, ArrowLeft } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/useAuth";
import { authenticatedRequest, ApiError } from "../api/client";
import { Loading } from "../components/ui";
import { Badge, type BadgeTone } from "../components/ui";
import type {
  ConnectorDto,
  OrchestratedPaymentDto,
  PaymentListResponse,
  RoutingRuleDto,
} from "../types/orchestration";
import { PageHeader } from "../components/layout/PageHeader";

/**
 * OrchestrationDashboard — 支付编排控制台。
 *
 * 2026-09-16 审查修复：
 *  1. 补请求竞态守卫（fetchSeq）—— 此前 8s 轮询 + 单请求最长 30s 超时必然产生
 *     并发在途请求，旧轮次响应会覆盖新轮次已渲染的数据。HomePage 已有该守卫，
 *     本页遗漏。
 *  2. loading 只在首轮置位 —— 此前每轮轮询先 setLoading(true)，导致页面每 8 秒
 *     被整页替换为 Spinner（闪白），无法持续阅读。
 *  3. 移除请求中的 `merchantId=1` —— 服务端（PaymentOrchestrationController
 *     listPayments）显式忽略该参数、强制按认证商户过滤，保留它只会误导读者。
 *  4. 补齐 i18n —— 此前 tab 名、连接器元信息、路由元信息、aria-label 均为硬编码英文。
 *  5. Tab 补 role="tablist"/"tab"/aria-selected 语义。
 *  6. 类型抽到 `types/orchestration.ts` 并标注命名约定边界（gateway 为 snake_case，
 *     与 core RPC 桥接的 camelCase 属不同契约）。
 *  7. 补返回首页入口与空态文案。
 */
const OrchestrationDashboard: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { apiKey, apiSecret, isAuthenticated } = useAuth();

  const [payments, setPayments] = useState<OrchestratedPaymentDto[]>([]);
  const [connectors, setConnectors] = useState<ConnectorDto[]>([]);
  const [rules, setRules] = useState<RoutingRuleDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorState>({ kind: null, message: "" });
  const [tab, setTab] = useState<"payments" | "connectors" | "rules">("payments");

  // 请求竞态守卫：只有最新一轮响应才允许写入 state
  const fetchSeq = useRef(0);
  // 首轮标记：仅首轮显示整页 loading，后续轮询静默刷新
  const isFirstLoad = useRef(true);

  const fetchData = useCallback(async () => {
    // Fail fast with an explicit auth prompt instead of attempting requests
    // that are guaranteed to 401. This replaces the previous silent swallow.
    if (!isAuthenticated) {
      setLoading(false);
      isFirstLoad.current = false;
      setError({ kind: "auth", message: t("orchestration.authRequired") });
      setPayments([]);
      setConnectors([]);
      setRules([]);
      return;
    }

    const seq = ++fetchSeq.current;
    if (isFirstLoad.current) setLoading(true);
    setError({ kind: null, message: "" });

    try {
      // Sequential awaits so the first 401 short-circuits the rest instead
      // of being masked by Promise.all's allSettled-like error semantics.
      // 注意：不传 merchantId —— 服务端按认证商户强制过滤。
      const pRes = await authenticatedRequest<PaymentListResponse>(
        "/api/v1/payments?limit=20",
        { method: "GET", apiKey, apiSecret },
      );
      const cRes = await authenticatedRequest<ConnectorDto[]>(
        "/api/v1/payments/connectors",
        { method: "GET", apiKey, apiSecret },
      );
      const rRes = await authenticatedRequest<RoutingRuleDto[]>(
        "/api/v1/payments/routing-rules",
        { method: "GET", apiKey, apiSecret },
      );

      if (seq !== fetchSeq.current) return; // 旧响应晚到，丢弃

      setPayments(pRes?.data ?? []);
      setConnectors(Array.isArray(cRes) ? cRes : []);
      setRules(Array.isArray(rRes) ? rRes : []);
    } catch (err) {
      if (seq !== fetchSeq.current) return;

      if (err instanceof ApiError && err.status === 401) {
        setError({ kind: "auth", message: t("orchestration.authRequired") });
      } else if (err instanceof ApiError) {
        setError({
          kind: "network",
          message: t("orchestration.requestFailed", {
            status: err.status,
            message: err.message,
          }),
        });
      } else {
        setError({
          kind: "network",
          message: err instanceof Error ? err.message : t("orchestration.unknownError"),
        });
      }
      // Clear stale data so the UI does not show pre-error snapshots.
      setPayments([]);
      setConnectors([]);
      setRules([]);
    } finally {
      if (seq === fetchSeq.current) {
        isFirstLoad.current = false;
        setLoading(false);
      }
    }
  }, [apiKey, apiSecret, isAuthenticated, t]);

  useEffect(() => {
    fetchData();
    const iv = setInterval(fetchData, 8000);
    return () => clearInterval(iv);
  }, [fetchData]);

  /** 支付状态 → Badge tone 映射（替代散落的 text-emerald-400 等魔法色）。 */
  const statusTone = (s: string): BadgeTone => {
    switch (s) {
      case "SUCCEEDED":
        return "success";
      case "PROCESSING":
        return "warning";
      case "FAILED":
        return "danger";
      default:
        return "neutral";
    }
  };

  const TABS = ["payments", "connectors", "rules"] as const;
  const TAB_LABEL: Record<(typeof TABS)[number], string> = {
    payments: t("orchestration.tabPayments"),
    connectors: t("orchestration.tabConnectors"),
    rules: t("orchestration.tabRules"),
  };

  return (
    <div className="min-h-screen bg-bg text-fg">
      <PageHeader maxWidth="max-w-6xl" innerClassName="justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <Link
              to="/"
              className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm shrink-0 transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
            >
              <ArrowLeft size={14} />
              {t("orchestration.back")}
            </Link>
            <span className="text-xs text-muted font-mono truncate hidden sm:inline">
              {t("orchestration.title")}
            </span>
          </div>
          <div
            className="flex gap-1 shrink-0"
            role="tablist"
            aria-label={t("orchestration.title")}
          >
            {TABS.map((tabName) => (
              <button
                key={tabName}
                type="button"
                role="tab"
                aria-selected={tab === tabName}
                onClick={() => setTab(tabName)}
                className={`px-3 py-1.5 rounded-sm text-xs font-medium transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus ${
                  tab === tabName
                    ? "bg-accent-solid text-accent-on"
                    : "text-fg-2 hover:text-fg hover:bg-accent-soft"
                }`}
              >
                {TAB_LABEL[tabName]}
              </button>
            ))}
          </div>
      </PageHeader>

      <main className="max-w-6xl mx-auto px-4 py-6">
        {/* Explicit auth-required banner (replaces silent .catch(() => ({ data: [] }))). */}
        {error.kind === "auth" && (
          <div className="mb-6 px-4 py-3 rounded-md border border-warn/60 bg-warn/10 text-warn text-sm flex items-center justify-between gap-3">
            <span className="flex items-center gap-2">
              <ShieldAlert size={16} strokeWidth={2} aria-hidden="true" />
              {error.message}
            </span>
            <Link
              to="/settings"
              className="text-xs font-medium underline shrink-0 hover:opacity-80"
            >
              {t("orchestration.goToSettings")}
            </Link>
          </div>
        )}

        {/* Generic network/transport error banner. */}
        {error.kind === "network" && (
          <div className="mb-6 px-4 py-3 rounded-md border border-danger/60 bg-danger/10 text-danger text-sm flex items-center gap-2">
            <AlertTriangle size={16} strokeWidth={2} aria-hidden="true" />
            {error.message}
          </div>
        )}

        {loading && (
          <div className="py-12 flex justify-center">
            <Loading label={t("common.loading")} />
          </div>
        )}

        {!loading && tab === "payments" && (
          <div className="space-y-2">
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-caps mb-3">
              {t("orchestration.recentPayments")}
            </h2>
            {payments.length === 0 && (
              <p className="text-muted text-sm">{t("orchestration.noPayments")}</p>
            )}
            {payments.map((p) => (
              <div
                key={p.id}
                className="bg-surface border border-border rounded-lg px-4 py-3 flex items-center justify-between gap-4"
              >
                <div className="min-w-0">
                  <span className="font-mono text-xs text-accent break-all">{p.id}</span>
                  <div className="text-xs text-muted mt-0.5 truncate">
                    {t("orchestration.fieldConnector")}: {p.connector ?? "—"} ·{" "}
                    {p.created_at ? new Date(p.created_at).toLocaleString(i18n.language) : "—"}
                  </div>
                </div>
                <div className="text-right shrink-0">
                  <span className="text-sm font-medium text-fg">
                    {p.amount} {p.currency}
                  </span>
                  <div className="mt-0.5">
                    <Badge tone={statusTone(p.status)}>{p.status}</Badge>
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {!loading && tab === "connectors" && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-caps col-span-full mb-1">
              {t("orchestration.connectors")}
            </h2>
            {connectors.length === 0 && (
              <p className="text-muted text-sm col-span-full">
                {t("orchestration.noConnectors")}
              </p>
            )}
            {connectors.map((c) => (
              <div key={c.id} className="bg-surface border border-border rounded-lg p-4">
                <div className="flex items-center justify-between gap-3">
                  <span className="font-medium text-sm text-fg truncate">
                    {c.display_name}
                  </span>
                  <Badge
                    tone={c.active ? "success" : "neutral"}
                    icon={null}
                  >
                    {c.active
                      ? t("orchestration.connectorStateActive")
                      : t("orchestration.connectorStateInactive")}
                  </Badge>
                </div>
                <div className="text-xs text-muted mt-1">
                  {t("orchestration.connectorMeta", {
                    id: c.id,
                    type: c.type,
                    fee: c.fee_bps,
                  })}
                </div>
                <div className="text-xs text-muted mt-0.5">
                  {t("orchestration.currencies")}:{" "}
                  {c.currencies?.length
                    ? c.currencies.join(", ")
                    : t("orchestration.currenciesAll")}
                </div>
              </div>
            ))}
          </div>
        )}

        {!loading && tab === "rules" && (
          <div className="space-y-2">
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-caps mb-3">
              {t("orchestration.routingRules")}
            </h2>
            {rules.length === 0 && (
              <p className="text-muted text-sm">{t("orchestration.noRules")}</p>
            )}
            {rules.map((r) => (
              <div key={r.id} className="bg-surface border border-border rounded-lg px-4 py-3">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-sm font-medium text-fg truncate">{r.name}</span>
                  <Badge tone="primary" icon={null}>
                    {r.strategy}
                  </Badge>
                </div>
                <div className="text-xs text-muted mt-1">
                  {t("orchestration.ruleMeta", {
                    connectors: r.connectors?.join(" → ") ?? "—",
                    priority: r.priority,
                  })}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
};

type ErrorKind = "auth" | "network" | null;

interface ErrorState {
  kind: ErrorKind;
  message: string;
}

export default OrchestrationDashboard;
