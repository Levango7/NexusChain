import React, { useState, useEffect, useCallback } from "react";
import { AlertTriangle, ShieldAlert } from "lucide-react";
import { useTranslation } from "react-i18next";
import { useAuth } from "../auth/useAuth";
import { authenticatedRequest, ApiError } from "../api/client";
import { Loading } from "../components/ui";
import { Badge, type BadgeTone } from "../components/ui";

interface Payment {
  id: string;
  status: string;
  amount: number;
  currency: string;
  connector: string;
  transaction_hash: string | null;
  created_at: string;
  confirmed_at: string | null;
}

interface PaymentListResponse {
  data: Payment[];
}

interface Connector {
  id: string;
  type: string;
  display_name: string;
  active: boolean;
  fee_bps: number;
  currencies: string[];
}

interface RoutingRule {
  id: string;
  name: string;
  strategy: string;
  connectors: string[];
  priority: number;
}

type ErrorKind = "auth" | "network" | null;

interface ErrorState {
  kind: ErrorKind;
  message: string;
}

/**
 * OrchestrationDashboard — 支付编排控制台。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens（bg / surface / fg / muted / accent / warn / danger / success）
 *   - emoji ⚠ 替换为 lucide-react <AlertTriangle /> / <ShieldAlert />
 *   - 状态色（text-emerald-400 等）替换为 Badge tone 映射
 *   - Loading 文案替换为 <Loading /> 组件
 *   - 间距 / 圆角 / 字体统一 token
 */
const OrchestrationDashboard: React.FC = () => {
  const { t } = useTranslation();
  const { apiKey, apiSecret, isAuthenticated } = useAuth();

  const [payments, setPayments] = useState<Payment[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [rules, setRules] = useState<RoutingRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ErrorState>({ kind: null, message: "" });
  const [tab, setTab] = useState<"payments" | "connectors" | "rules">(
    "payments",
  );

  const fetchData = useCallback(async () => {
    // Fail fast with an explicit auth prompt instead of attempting requests
    // that are guaranteed to 401. This replaces the previous silent swallow.
    if (!isAuthenticated) {
      setLoading(false);
      setError({
        kind: "auth",
        message: t("orchestration.authRequired"),
      });
      setPayments([]);
      setConnectors([]);
      setRules([]);
      return;
    }

    setLoading(true);
    setError({ kind: null, message: "" });

    try {
      // Sequential awaits so the first 401 short-circuits the rest instead
      // of being masked by Promise.all's allSettled-like error semantics.
      const pRes = await authenticatedRequest<PaymentListResponse>(
        "/api/v1/payments?merchantId=1&limit=20",
        { method: "GET", apiKey, apiSecret },
      );
      const cRes = await authenticatedRequest<Connector[]>(
        "/api/v1/payments/connectors",
        { method: "GET", apiKey, apiSecret },
      );
      const rRes = await authenticatedRequest<RoutingRule[]>(
        "/api/v1/payments/routing-rules",
        { method: "GET", apiKey, apiSecret },
      );

      setPayments(pRes?.data ?? []);
      setConnectors(Array.isArray(cRes) ? cRes : []);
      setRules(Array.isArray(rRes) ? rRes : []);
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError({
          kind: "auth",
          message: t("orchestration.authRequired"),
        });
      } else if (err instanceof ApiError) {
        setError({
          kind: "network",
          message: t("orchestration.requestFailed", { status: err.status, message: err.message }),
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
      setLoading(false);
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

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-lg font-bold text-accent">NexusChain</span>
            <span className="text-xs text-muted font-mono">
              {t("orchestration.title")}
            </span>
          </div>
          <div className="flex gap-1">
            {(["payments", "connectors", "rules"] as const).map((t) => (
              <button
                key={t}
                onClick={() => setTab(t)}
                className={`px-3 py-1.5 rounded-sm text-xs font-medium transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus ${
                  tab === t
                    ? "bg-accent text-accent-on"
                    : "text-fg-2 hover:text-fg hover:bg-accent-soft"
                }`}
              >
                {t.charAt(0).toUpperCase() + t.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </header>

      <main className="max-w-6xl mx-auto px-4 py-6">
        {/* Explicit auth-required banner (replaces silent .catch(() => ({ data: [] }))). */}
        {error.kind === "auth" && (
          <div className="mb-6 px-4 py-3 rounded-md border border-warn/60 bg-warn/10 text-warn text-sm flex items-center justify-between gap-3">
            <span className="flex items-center gap-2">
              <ShieldAlert size={16} strokeWidth={2} />
              {error.message}
            </span>
            <span className="text-xs text-warn/80 font-mono">
              {isAuthenticated ? t("orchestration.credentialRejected") : t("orchestration.notConfigured")}
            </span>
          </div>
        )}

        {/* Generic network/transport error banner. */}
        {error.kind === "network" && (
          <div className="mb-6 px-4 py-3 rounded-md border border-danger/60 bg-danger/10 text-danger text-sm flex items-center gap-2">
            <AlertTriangle size={16} strokeWidth={2} />
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
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-wide mb-3">
              {t("orchestration.recentPayments")}
            </h2>
            {payments.length === 0 && (
              <p className="text-muted text-sm">{t("orchestration.noPayments")}</p>
            )}
            {payments.map((p) => (
              <div
                key={p.id}
                className="bg-surface border border-border rounded-lg px-4 py-3 flex items-center justify-between"
              >
                <div>
                  <span className="font-mono text-xs text-accent">{p.id}</span>
                  <div className="text-xs text-muted mt-0.5">
                    {p.connector} · {p.created_at?.slice(0, 19)}
                  </div>
                </div>
                <div className="text-right">
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
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-wide col-span-full mb-1">
              {t("orchestration.connectors")}
            </h2>
            {connectors.map((c) => (
              <div
                key={c.id}
                className="bg-surface border border-border rounded-lg p-4"
              >
                <div className="flex items-center justify-between">
                  <span className="font-medium text-sm text-fg">
                    {c.display_name}
                  </span>
                  <span
                    className={`w-2 h-2 rounded-full ${
                      c.active ? "bg-success" : "bg-danger"
                    }`}
                    aria-label={c.active ? "active" : "inactive"}
                  />
                </div>
                <div className="text-xs text-muted mt-1">
                  ID: {c.id} · Type: {c.type} · Fee: {c.fee_bps} bps
                </div>
                <div className="text-xs text-muted mt-0.5">
                  Currencies:{" "}
                  {c.currencies?.length ? c.currencies.join(", ") : "ALL"}
                </div>
              </div>
            ))}
          </div>
        )}

        {!loading && tab === "rules" && (
          <div className="space-y-2">
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-wide mb-3">
              {t("orchestration.routingRules")}
            </h2>
            {rules.map((r) => (
              <div
                key={r.id}
                className="bg-surface border border-border rounded-lg px-4 py-3"
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-fg">{r.name}</span>
                  <Badge tone="primary">{r.strategy}</Badge>
                </div>
                <div className="text-xs text-muted mt-1">
                  Connectors: {r.connectors?.join(" → ")} · Priority:{" "}
                  {r.priority}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
};

export default OrchestrationDashboard;
