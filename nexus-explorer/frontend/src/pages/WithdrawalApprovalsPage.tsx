import React, { useState, useEffect, useCallback, useRef } from "react";
import { Link } from "react-router-dom";
import {
  AlertTriangle,
  ShieldAlert,
  ArrowLeft,
  CheckCircle2,
  XCircle,
  PlayCircle,
  RefreshCw,
  KeyRound,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button, Card, Modal, Badge, Loading, type BadgeTone } from "../components/ui";
import { PageHeader } from "../components/layout/PageHeader";
import {
  loadWalletConnection,
  saveWalletConnection,
  isWalletConfigured,
  listPendingWithdrawals,
  getCustodyBalance,
  checkWhitelist,
  approveWithdrawal,
  rejectWithdrawal,
  executeWithdrawal,
  WalletApiError,
  DEFAULT_WALLET_BASE,
  type WalletConnection,
  type WithdrawalRequestDto,
  type CustodyBalanceDto,
} from "../api/wallet";

/**
 * WithdrawalApprovalsPage — 托管钱包提现审批台。
 *
 * <h3>为什么这个页面需要单独说明鉴权</h3>
 * 本仓存在两套互不相通的鉴权：网关用四路 HMAC（`api/client.ts`），
 * wallet-service 用 JWT Bearer（`JwtAuthenticationFilter`）。
 * 而**前端此前没有任何 JWT 通道**——所以本页首次引入
 * 「wallet 连接配置」（base URL + JWT），存 sessionStorage。
 *
 * <p><b>这是已知的临时形态，不是终态。</b> 终态应由统一登录签发 JWT；
 * 在那之前，页面把"需要什么凭证"显式摆在用户面前，而不是假装能连。</p>
 *
 * <h3>为什么审批不经网关代理</h3>
 * 网关的 `FeignJwtRequestInterceptor` 会自签 `subject=nexus-gateway` 的服务 JWT。
 * 若由网关代理审批，网关就会以 APPROVER 身份代所有人批准——审批流形同虚设。
 * 故本页直连 wallet-service，JWT 由人（运维/审批人）提供。
 *
 * <h3>页面做四件事</h3>
 * <ol>
 *   <li>列待审批队列（`GET /withdrawal/pending`）；</li>
 *   <li>对每笔**自动核对收款地址是否在白名单**（`GET /whitelist/check`）——
 *       未加白的提现是高风险信号，必须在批准前看到；</li>
 *   <li>显示托管余额（`GET /custody/balance`）作为上下文；</li>
 *   <li>批准 / 拒绝（必填原因）/ 执行（仅 APPROVED 可用）。</li>
 * </ol>
 */
const WithdrawalApprovalsPage: React.FC = () => {
  const { t, i18n } = useTranslation();

  const [conn, setConn] = useState<WalletConnection>(() => loadWalletConnection());
  const [draftBase, setDraftBase] = useState<string>(() => loadWalletConnection().baseUrl);
  const [draftJwt, setDraftJwt] = useState<string>("");

  const [items, setItems] = useState<WithdrawalRequestDto[]>([]);
  const [balance, setBalance] = useState<CustodyBalanceDto | null>(null);
  /** 地址 → 是否在白名单；null = 尚未查/查询失败 */
  const [whitelist, setWhitelist] = useState<Record<string, boolean | null>>({});

  const [loading, setLoading] = useState(false);
  /** 手动刷新中的独立态：避免与"首轮整页 loading"耦合（首轮只允许一次整页 Spinner）。 */
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<ErrorState>({ kind: null, message: "" });
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string>("");

  const [rejectTarget, setRejectTarget] = useState<WithdrawalRequestDto | null>(null);
  const [rejectReason, setRejectReason] = useState<string>("");

  // 请求竞态守卫：只有最新一轮响应才允许写入 state（与 OrchestrationDashboard 同构）
  const fetchSeq = useRef(0);
  const isFirstLoad = useRef(true);

  const configured = isWalletConfigured(conn);

  /** 把 WalletApiError 翻译成用户能理解的三类错误。 */
  const describeError = useCallback(
    (err: unknown): ErrorState => {
      if (err instanceof WalletApiError) {
        if (err.status === 401) {
          return { kind: "auth", message: t("approvals.authInvalid") };
        }
        if (err.status === 403) {
          return { kind: "auth", message: t("approvals.forbidden") };
        }
        return {
          kind: "network",
          message: t("approvals.requestFailed", { status: err.status, message: err.message }),
        };
      }
      return {
        kind: "network",
        message: err instanceof Error ? err.message : t("approvals.unknownError"),
      };
    },
    [t],
  );

  const fetchData = useCallback(
    async (opts: { manual?: boolean } = {}) => {
      if (!configured) {
        setItems([]);
        setBalance(null);
        setWhitelist({});
        setLoading(false);
        setRefreshing(false);
        isFirstLoad.current = false;
        return;
      }

      const seq = ++fetchSeq.current;
      if (isFirstLoad.current) setLoading(true);
      if (opts.manual) setRefreshing(true);
      setError({ kind: null, message: "" });

      try {
        // 顺序 await：首个 401 立即短路，避免被 Promise.all 掩盖
        const pending = await listPendingWithdrawals(conn);
        const bal = await getCustodyBalance(conn);

        if (seq !== fetchSeq.current) return;

        const list = Array.isArray(pending) ? pending : [];
        setItems(list);
        setBalance(bal ?? null);

        // 白名单核对：对每个收款地址并发查询；单条失败只标记为 null，不影响整页
        const addresses = Array.from(new Set(list.map((x) => x.toAddress).filter(Boolean)));
        const results = await Promise.all(
          addresses.map(async (addr) => {
            try {
              const r = await checkWhitelist(conn, addr);
              return [addr, Boolean(r?.whitelisted)] as const;
            } catch {
              return [addr, null] as const;
            }
          }),
        );
        if (seq !== fetchSeq.current) return;
        setWhitelist(Object.fromEntries(results));
      } catch (err) {
        if (seq !== fetchSeq.current) return;
        setError(describeError(err));
        setItems([]);
        setBalance(null);
        setWhitelist({});
      } finally {
        if (seq === fetchSeq.current) {
          isFirstLoad.current = false;
          setLoading(false);
          setRefreshing(false);
        }
      }
    },
    [configured, conn, describeError],
  );

  useEffect(() => {
    fetchData();
    // 30s 轮询：审批队列不需要 8s 那么密，但也不能让人看陈旧数据
    const iv = setInterval(() => {
      fetchData();
    }, 30_000);
    return () => clearInterval(iv);
  }, [fetchData]);

  /** 保存连接并立即拉一次。 */
  const handleConnect = (e: React.FormEvent) => {
    e.preventDefault();
    const next: WalletConnection = { baseUrl: draftBase || DEFAULT_WALLET_BASE, jwt: draftJwt };
    saveWalletConnection(next);
    setConn(next);
    setDraftJwt("");
    isFirstLoad.current = true;
  };

  /** 断开（清 JWT）。 */
  const handleDisconnect = () => {
    const next: WalletConnection = { baseUrl: conn.baseUrl, jwt: "" };
    saveWalletConnection(next);
    setConn(next);
    setItems([]);
    setBalance(null);
    setWhitelist({});
  };

  /** 统一的动作执行器：置 busy、调接口、刷新、错误归一。 */
  const runAction = async (id: string, fn: () => Promise<unknown>) => {
    setBusyId(id);
    setActionError("");
    try {
      await fn();
      await fetchData();
    } catch (err) {
      const d = describeError(err);
      setActionError(d.message);
    } finally {
      setBusyId(null);
    }
  };

  const statusTone = (s: WithdrawalRequestDto["status"]): BadgeTone => {
    switch (s) {
      case "EXECUTED":
        return "success";
      case "APPROVED":
      case "EXECUTING":
        return "warning";
      case "FAILED":
      case "REJECTED":
        return "danger";
      default:
        return "neutral";
    }
  };

  const fmtTime = (v: string | null): string =>
    v ? new Date(v).toLocaleString(i18n.language) : "—";

  const wlBadge = (addr: string) => {
    const state = whitelist[addr];
    if (state === undefined || state === null) {
      return (
        <Badge tone="neutral" icon={null}>
          {t("approvals.whitelistUnknown")}
        </Badge>
      );
    }
    return state ? (
      <Badge tone="success" icon={null}>
        {t("approvals.whitelisted")}
      </Badge>
    ) : (
      <Badge tone="danger" icon={null}>
        {t("approvals.notWhitelisted")}
      </Badge>
    );
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
            {t("approvals.back")}
          </Link>
          <span className="text-xs text-muted font-mono truncate hidden sm:inline">
            {t("approvals.title")}
          </span>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {configured && (
            <>
              <Button
                variant="ghost"
                size="sm"
                leadingIcon={<RefreshCw size={14} />}
                onClick={() => fetchData({ manual: true })}
                loading={refreshing}
              >
                {t("common.refresh")}
              </Button>
              <Button variant="ghost" size="sm" onClick={handleDisconnect}>
                {t("approvals.disconnect")}
              </Button>
            </>
          )}
        </div>
      </PageHeader>

      <main className="max-w-6xl mx-auto px-4 py-6">
        {/* === 连接配置（未配置或需要重配时显示）=== */}
        {!configured && (
          <Card
            title={
              <span className="flex items-center gap-2">
                <KeyRound size={16} aria-hidden="true" />
                {t("approvals.connectTitle")}
              </span>
            }
            subtitle={t("approvals.connectSubtitle")}
            className="max-w-2xl"
          >
            <form onSubmit={handleConnect} className="space-y-4">
              <div>
                <label htmlFor="wallet-base" className="block text-xs font-medium text-fg-2 mb-1">
                  {t("approvals.baseUrl")}
                </label>
                <input
                  id="wallet-base"
                  type="url"
                  value={draftBase}
                  onChange={(e) => setDraftBase(e.target.value)}
                  placeholder={DEFAULT_WALLET_BASE}
                  className="w-full bg-surface-2 border border-border rounded-md px-3 py-2 text-sm font-mono text-fg placeholder:text-muted focus:outline-none focus-visible:shadow-focus"
                />
              </div>
              <div>
                <label htmlFor="wallet-jwt" className="block text-xs font-medium text-fg-2 mb-1">
                  {t("approvals.jwt")}
                </label>
                <textarea
                  id="wallet-jwt"
                  value={draftJwt}
                  onChange={(e) => setDraftJwt(e.target.value)}
                  rows={4}
                  placeholder={t("approvals.jwtPlaceholder")}
                  className="w-full bg-surface-2 border border-border rounded-md px-3 py-2 text-xs font-mono text-fg placeholder:text-muted focus:outline-none focus-visible:shadow-focus resize-y"
                />
                <p className="text-xs text-muted mt-1">{t("approvals.jwtHint")}</p>
              </div>
              <div className="flex items-center gap-3">
                <Button type="submit" variant="primary" size="sm" disabled={!draftJwt.trim()}>
                  {t("approvals.connect")}
                </Button>
                <span className="text-xs text-muted">{t("approvals.sessionOnly")}</span>
              </div>
            </form>
          </Card>
        )}

        {/* === 错误横幅 === */}
        {error.kind === "auth" && (
          <div className="mb-6 px-4 py-3 rounded-md border border-warn/60 bg-warn/10 text-warn text-sm flex items-center justify-between gap-3">
            <span className="flex items-center gap-2">
              <ShieldAlert size={16} strokeWidth={2} aria-hidden="true" />
              {error.message}
            </span>
            <Button variant="ghost" size="sm" onClick={handleDisconnect}>
              {t("approvals.reconfigure")}
            </Button>
          </div>
        )}
        {error.kind === "network" && (
          <div className="mb-6 px-4 py-3 rounded-md border border-danger/60 bg-danger/10 text-danger text-sm flex items-center gap-2">
            <AlertTriangle size={16} strokeWidth={2} aria-hidden="true" />
            {error.message}
          </div>
        )}
        {actionError && (
          <div className="mb-6 px-4 py-3 rounded-md border border-danger/60 bg-danger/10 text-danger text-sm flex items-center gap-2">
            <AlertTriangle size={16} strokeWidth={2} aria-hidden="true" />
            {actionError}
          </div>
        )}

        {loading && (
          <div className="py-12 flex justify-center">
            <Loading label={t("common.loading")} />
          </div>
        )}

        {configured && !loading && (
          <>
            {/* === 托管余额上下文 === */}
            {balance && (
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mb-6">
                <div className="bg-surface border border-border rounded-lg px-4 py-3">
                  <div className="text-xs text-muted uppercase tracking-caps">
                    {t("approvals.hotBalance")}
                  </div>
                  <div className="text-lg font-medium text-fg mt-1 font-mono">
                    {balance.hot != null ? String(balance.hot) : "—"}
                  </div>
                </div>
                <div className="bg-surface border border-border rounded-lg px-4 py-3">
                  <div className="text-xs text-muted uppercase tracking-caps">
                    {t("approvals.coldBalance")}
                  </div>
                  <div className="text-lg font-medium text-fg mt-1 font-mono">
                    {balance.cold != null ? String(balance.cold) : "—"}
                  </div>
                </div>
              </div>
            )}

            {/* === 待审批队列 === */}
            <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-caps mb-3">
              {t("approvals.pendingTitle", { n: items.length })}
            </h2>

            {items.length === 0 && <p className="text-muted text-sm">{t("approvals.noPending")}</p>}

            <div className="space-y-3">
              {items.map((it) => {
                const notWhitelisted = whitelist[it.toAddress] === false;
                const busy = busyId === it.requestId;
                return (
                  <div
                    key={it.requestId}
                    className={`bg-surface border rounded-lg px-4 py-3 ${
                      notWhitelisted ? "border-danger/60" : "border-border"
                    }`}
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="text-base font-medium text-fg font-mono">
                            {String(it.amount)} {it.currency}
                          </span>
                          <Badge tone={statusTone(it.status)}>{it.status}</Badge>
                          {wlBadge(it.toAddress)}
                        </div>
                        <div className="text-xs text-muted mt-1 break-all font-mono">
                          {it.toAddress}
                        </div>
                        <div className="text-xs text-muted mt-1">
                          {t("approvals.meta", {
                            approved: it.approvedCount,
                            required: it.requiredApprovers ?? "—",
                            time: fmtTime(it.createdAt),
                          })}
                        </div>
                        {notWhitelisted && (
                          <div className="text-xs text-danger mt-1 flex items-center gap-1">
                            <AlertTriangle size={12} aria-hidden="true" />
                            {t("approvals.notWhitelistedWarn")}
                          </div>
                        )}
                      </div>

                      <div className="flex items-center gap-2 shrink-0">
                        <Button
                          variant="primary"
                          size="sm"
                          leadingIcon={<CheckCircle2 size={14} />}
                          loading={busy}
                          onClick={() =>
                            runAction(it.requestId, () => approveWithdrawal(conn, it.requestId))
                          }
                        >
                          {t("approvals.approve")}
                        </Button>
                        <Button
                          variant="danger"
                          size="sm"
                          leadingIcon={<XCircle size={14} />}
                          disabled={busy}
                          onClick={() => {
                            setRejectTarget(it);
                            setRejectReason("");
                          }}
                        >
                          {t("approvals.reject")}
                        </Button>
                      </div>
                    </div>

                    {/* 已获批准（或执行中/执行完）时，给出"执行"入口 */}
                    {(it.status === "APPROVED" || it.status === "EXECUTING") && (
                      <div className="mt-3 pt-3 border-t border-border flex items-center justify-between gap-3">
                        <span className="text-xs text-muted">{t("approvals.executeHint")}</span>
                        <Button
                          variant="secondary"
                          size="sm"
                          leadingIcon={<PlayCircle size={14} />}
                          loading={busy}
                          onClick={() =>
                            runAction(it.requestId, () => executeWithdrawal(conn, it.requestId))
                          }
                        >
                          {t("approvals.execute")}
                        </Button>
                      </div>
                    )}

                    {it.chainTxHash && (
                      <div className="mt-2 text-xs text-muted font-mono break-all">
                        tx: {it.chainTxHash}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          </>
        )}
      </main>

      {/* === 拒绝原因弹窗（wallet-service 的 reject 要求 reason）=== */}
      <Modal
        open={rejectTarget !== null}
        onClose={() => setRejectTarget(null)}
        title={t("approvals.rejectTitle")}
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setRejectTarget(null)}>
              {t("common.cancel")}
            </Button>
            <Button
              variant="danger"
              size="sm"
              disabled={!rejectReason.trim()}
              onClick={() => {
                const target = rejectTarget;
                if (!target) return;
                const reason = rejectReason.trim();
                setRejectTarget(null);
                setRejectReason("");
                runAction(target.requestId, () => rejectWithdrawal(conn, target.requestId, reason));
              }}
            >
              {t("approvals.reject")}
            </Button>
          </div>
        }
      >
        {rejectTarget && (
          <div className="space-y-3">
            <p className="text-sm text-fg-2">
              {t("approvals.rejectFor", {
                amount: String(rejectTarget.amount),
                currency: rejectTarget.currency,
              })}
            </p>
            <textarea
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              rows={3}
              placeholder={t("approvals.rejectReasonPlaceholder")}
              className="w-full bg-surface-2 border border-border rounded-md px-3 py-2 text-sm text-fg placeholder:text-muted focus:outline-none focus-visible:shadow-focus resize-y"
            />
            <p className="text-xs text-muted">{t("approvals.rejectReasonHint")}</p>
          </div>
        )}
      </Modal>
    </div>
  );
};

type ErrorKind = "auth" | "network" | null;

interface ErrorState {
  kind: ErrorKind;
  message: string;
}

export default WithdrawalApprovalsPage;
