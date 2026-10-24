import React, { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { AlertTriangle, ArrowLeft, Coins, RefreshCw, TrendingUp } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button, Card, Badge, Loading, type BadgeTone } from "../components/ui";
import { PageHeader } from "../components/layout/PageHeader";
import { useAuth } from "../auth/useAuth";
import { ApiError } from "../api/client";
import {
  getMyFundDashboard,
  type AnomalyMonitorDto,
  type FundDashboardDto,
  type FundOverviewDto,
  type RecentTransactionDto,
  type TrendPointDto,
} from "../api/fundDashboard";

/**
 * FundDashboardPage — 商户资金看板。
 *
 * <h3>为什么只调一个端点</h3>
 * `GET /api/v1/fund-dashboard`（不带 merchantId）一次返回概览 / 趋势 / 明细 / 异常四块，
 * 服务端 30 秒缓存。前端不做并发四请求——那只会把同一份缓存查四遍。
 *
 * <p><b>为什么路径不带 merchantId</b>：商户侧身份由 `ApiKeyInterceptor` 从 API Key
 * 推导，客户端不持有自己的 merchantId（全仓无 `/merchants/me`）。带 id 的路径对
 * 商户控制台不可用。详见 `api/fundDashboard.ts` 顶部说明。</p>
 *
 * <h3>为什么不做自动轮询</h3>
 * 这是资金页面。数字在用户读的时候自己变，比"看到旧数字"更糟——用户可能正对着
 * 某个余额做判断。改为**手动刷新**，并在页脚显示数据生成时间，让"这份数据有多旧"
 * 始终可见。（审批台轮询 30s 是因为那里等的是别人的动作，不是自己的钱。）</p>
 */
const FundDashboardPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { apiKey, apiSecret, isAuthenticated } = useAuth();

  const [data, setData] = useState<FundDashboardDto | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string>("");

  // 请求竞态守卫：只有最新一轮响应才允许写入 state
  const seqRef = useRef(0);
  const firstLoadRef = useRef(true);

  const load = useCallback(
    async (opts: { manual?: boolean } = {}) => {
      if (!isAuthenticated) {
        setData(null);
        setError("");
        setLoading(false);
        setRefreshing(false);
        firstLoadRef.current = false;
        return;
      }

      const seq = ++seqRef.current;
      if (firstLoadRef.current) setLoading(true);
      if (opts.manual) setRefreshing(true);
      setError("");

      try {
        const res = await getMyFundDashboard({ apiKey, apiSecret });
        if (seq !== seqRef.current) return;
        setData(res ?? null);
      } catch (err) {
        if (seq !== seqRef.current) return;
        setData(null);
        if (err instanceof ApiError) {
          setError(
            err.status === 401
              ? t("fund.authInvalid")
              : t("fund.loadFailedWithStatus", { status: err.status, message: err.message }),
          );
        } else {
          setError(err instanceof Error ? err.message : t("fund.loadFailed"));
        }
      } finally {
        if (seq === seqRef.current) {
          firstLoadRef.current = false;
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [isAuthenticated, apiKey, apiSecret, t],
  );

  useEffect(() => {
    load();
  }, [load]);

  /** 金额：后端 BigDecimal 序列化为 number，也可能缺字段；统一成可读串。 */
  const fmtAmount = (v: unknown): string => {
    if (v === null || v === undefined || v === "") return "—";
    const n = typeof v === "number" ? v : Number(v);
    if (!Number.isFinite(n)) return String(v);
    return n.toLocaleString(i18n.language);
  };

  const fmtTime = (v: string | null | undefined): string =>
    v ? new Date(v).toLocaleString(i18n.language) : "—";

  /** 后端枚举名 → 本地化文案；未知值原样显示（不隐藏，便于发现新枚举）。 */
  const fmtDirection = (v: string | undefined): string => {
    if (v === "CREDIT") return t("fund.directionCredit");
    if (v === "DEBIT") return t("fund.directionDebit");
    return v ?? "—";
  };

  const statusTone = (status: string | undefined): BadgeTone => {
    if (!status || status === "NOT_CREATED") return "neutral";
    if (status === "ACTIVE") return "success";
    if (status === "FROZEN" || status === "CLOSED") return "danger";
    return "warning";
  };

  const overview: FundOverviewDto = data?.fundOverview ?? {};
  const trend: TrendPointDto[] = data?.transactionTrend ?? [];
  const recent: RecentTransactionDto[] = data?.recentTransactions ?? [];
  const anomaly: AnomalyMonitorDto = data?.anomalyMonitor ?? {};

  return (
    <div className="min-h-screen bg-bg text-fg">
      <PageHeader maxWidth="max-w-6xl" innerClassName="justify-between gap-4">
        <div className="flex items-center gap-3 min-w-0">
          <Link
            to="/"
            className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm shrink-0 transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
          >
            <ArrowLeft size={14} />
            {t("fund.back")}
          </Link>
          <span className="text-xs text-muted font-mono truncate hidden sm:inline">
            {t("fund.title")}
          </span>
        </div>
        {isAuthenticated && (
          <Button
            variant="ghost"
            size="sm"
            leadingIcon={<RefreshCw size={14} />}
            onClick={() => load({ manual: true })}
            loading={refreshing}
          >
            {t("common.refresh")}
          </Button>
        )}
      </PageHeader>

      <main className="max-w-6xl mx-auto px-4 py-6">
        {/* === 未配置凭证 === */}
        {!isAuthenticated && (
          <Card
            title={
              <span className="flex items-center gap-2">
                <Coins size={16} aria-hidden="true" />
                {t("fund.title")}
              </span>
            }
            subtitle={t("fund.needAuth")}
            className="max-w-2xl"
          >
            <Link to="/settings">
              <Button variant="primary" size="sm">
                {t("fund.goSettings")}
              </Button>
            </Link>
          </Card>
        )}

        {/* === 错误横幅 === */}
        {error && (
          <div className="mb-6 px-4 py-3 rounded-md border border-danger/60 bg-danger/10 text-danger text-sm flex items-center gap-2">
            <AlertTriangle size={16} strokeWidth={2} aria-hidden="true" />
            {error}
          </div>
        )}

        {loading && (
          <div className="py-12 flex justify-center">
            <Loading label={t("common.loading")} />
          </div>
        )}

        {isAuthenticated && !loading && data && (
          <>
            {/* === 资金概览 === */}
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-caps mb-3">
              {t("fund.overview")}
            </h2>
            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
              {[
                { key: "balance", label: t("fund.balance"), value: overview.balance },
                { key: "frozen", label: t("fund.frozen"), value: overview.frozen },
                { key: "reserve", label: t("fund.reserve"), value: overview.reserve },
                { key: "total", label: t("fund.totalAssets"), value: overview.totalAssets },
              ].map((c) => (
                <div key={c.key} className="bg-surface border border-border rounded-lg px-4 py-3">
                  <div className="text-xs text-muted uppercase tracking-caps">{c.label}</div>
                  <div className="text-lg font-medium text-fg mt-1 font-mono">
                    {fmtAmount(c.value)}
                  </div>
                </div>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-2 mb-6">
              <span className="text-xs text-muted">{t("fund.accountStatus")}</span>
              <Badge tone={statusTone(overview.balanceStatus)}>
                {overview.balanceStatus === "NOT_CREATED"
                  ? t("fund.statusNotCreated")
                  : (overview.balanceStatus ?? "—")}
              </Badge>
              {overview.alertFlag && (
                <>
                  <span className="text-xs text-muted ml-3">{t("fund.alertFlag")}</span>
                  <Badge tone="warning">{overview.alertFlag}</Badge>
                </>
              )}
            </div>

            {/* === 异常监控（放在趋势之前：有异常时它更重要）=== */}
            <Card
              title={
                <span className="flex items-center gap-2">
                  <AlertTriangle size={16} aria-hidden="true" />
                  {t("fund.anomaly")}
                </span>
              }
              subtitle={anomaly.hasAlert ? t("fund.anomalyAlert") : t("fund.anomalyOk")}
              actions={
                anomaly.hasAlert ? (
                  <Badge tone="danger">{anomaly.alertLevel ?? t("fund.anomalyLevelUnknown")}</Badge>
                ) : (
                  <Badge tone="success">{t("fund.anomalyOkShort")}</Badge>
                )
              }
              className="mb-6"
            >
              {(anomaly.anomalies ?? []).length === 0 ? (
                <p className="text-muted text-sm">{t("fund.anomalyEmpty")}</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-xs text-muted text-left">
                        <th className="py-2 pr-4 font-medium">{t("fund.colTime")}</th>
                        <th className="py-2 pr-4 font-medium">{t("fund.colType")}</th>
                        <th className="py-2 pr-4 font-medium text-right">{t("fund.colAmount")}</th>
                        <th className="py-2 font-medium text-right">{t("fund.colBalance")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(anomaly.anomalies ?? []).map((a, i) => (
                        <tr key={`${a.txNo ?? "tx"}-${i}`} className="border-t border-border-soft">
                          <td className="py-2 pr-4 text-muted font-mono text-xs">
                            {fmtTime(a.createdAt)}
                          </td>
                          <td className="py-2 pr-4">{a.operationType ?? "—"}</td>
                          <td className="py-2 pr-4 text-right font-mono">{fmtAmount(a.amount)}</td>
                          <td className="py-2 text-right font-mono">{fmtAmount(a.balanceAfter)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            {/* === 流水趋势 === */}
            <Card
              title={
                <span className="flex items-center gap-2">
                  <TrendingUp size={16} aria-hidden="true" />
                  {t("fund.trend")}
                </span>
              }
              className="mb-6"
            >
              {trend.length === 0 ? (
                <p className="text-muted text-sm">{t("fund.noTrend")}</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-xs text-muted text-left">
                        <th className="py-2 pr-4 font-medium">{t("fund.trendDate")}</th>
                        <th className="py-2 pr-4 font-medium text-right">
                          {t("fund.trendCredit")}
                        </th>
                        <th className="py-2 pr-4 font-medium text-right">{t("fund.trendDebit")}</th>
                        <th className="py-2 pr-4 font-medium text-right">{t("fund.trendNet")}</th>
                        <th className="py-2 font-medium text-right">{t("fund.trendCount")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {trend.map((p) => (
                        <tr key={p.date} className="border-t border-border-soft">
                          <td className="py-2 pr-4 font-mono text-xs">{p.date}</td>
                          <td className="py-2 pr-4 text-right font-mono">
                            {fmtAmount(p.totalCredit)}
                          </td>
                          <td className="py-2 pr-4 text-right font-mono">
                            {fmtAmount(p.totalDebit)}
                          </td>
                          <td className="py-2 pr-4 text-right font-mono">{fmtAmount(p.netFlow)}</td>
                          <td className="py-2 text-right font-mono text-muted">
                            {p.creditCount ?? 0}/{p.debitCount ?? 0}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            {/* === 近期流水 === */}
            <Card title={t("fund.recent")}>
              {recent.length === 0 ? (
                <p className="text-muted text-sm">{t("fund.noRecent")}</p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-xs text-muted text-left">
                        <th className="py-2 pr-4 font-medium">{t("fund.colTime")}</th>
                        <th className="py-2 pr-4 font-medium">{t("fund.colType")}</th>
                        <th className="py-2 pr-4 font-medium">{t("fund.colDirection")}</th>
                        <th className="py-2 pr-4 font-medium text-right">{t("fund.colAmount")}</th>
                        <th className="py-2 font-medium text-right">{t("fund.colBalance")}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {recent.map((tx, i) => (
                        <tr key={`${tx.txNo ?? "tx"}-${i}`} className="border-t border-border-soft">
                          <td className="py-2 pr-4 text-muted font-mono text-xs">
                            {fmtTime(tx.createdAt)}
                          </td>
                          <td className="py-2 pr-4">{tx.operationType ?? "—"}</td>
                          <td className="py-2 pr-4">{fmtDirection(tx.direction)}</td>
                          <td className="py-2 pr-4 text-right font-mono">{fmtAmount(tx.amount)}</td>
                          <td className="py-2 text-right font-mono">
                            {fmtAmount(tx.balanceAfter)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </Card>

            {/* === 数据新鲜度（必须可见：本页不自动刷新）=== */}
            <p className="text-xs text-muted mt-4 text-center">
              {t("fund.generatedAt", { time: fmtTime(data.generatedAt) })}
            </p>
          </>
        )}
      </main>
    </div>
  );
};

export default FundDashboardPage;
