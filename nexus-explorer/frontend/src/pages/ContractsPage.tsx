import React, { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AlertCircle, FileCode2 } from "lucide-react";
import { api } from "../api/client";
import type { ContractInfo } from "../types";
import { Loading } from "../components/ui";
import { PageHeader, BackLink } from "../components/layout/PageHeader";
import { orDash } from "../utils/value";
import { formatRelativeTime } from "../utils/time";

/**
 * ContractsPage — 已部署合约列表。
 *
 * 背景：README 的「功能」清单从首版起就承诺「合约查看 — 浏览已部署的 WASM
 * 智能合约」，后端 `GET /api/contracts` 也一直存在，但前端**没有任何页面或
 * 入口消费它** —— 后端能力对用户不可达。本页补齐该链路：
 * 列表 → `/contracts/:addr` 详情 → 返回。
 *
 * 契约（勿改成「前端想象中的形状」）：
 *   字段来自 nexus-explorer/backend/src/rpc.ts#RpcContract，BFF 对 core
 *   `nexus_getContractList` 纯透传。`abi` 为可选，UI 不得渲染出 undefined。
 */
const ContractsPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const [contracts, setContracts] = useState<ContractInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const list = await api.getContracts();
        setContracts(list);
        setError(null);
      } catch (err) {
        console.error("API请求失败:", err);
        // 失败时清空列表：留着旧数据会让用户把「拉取失败」误读为「合约被删」
        setContracts([]);
        setError(err instanceof Error ? err.message : t("contracts.loadFailed"));
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="min-h-screen bg-bg text-fg">
      <PageHeader maxWidth="max-w-6xl" innerClassName="justify-between gap-4">
        <div className="flex items-center gap-3 min-w-0">
          <BackLink>{t("contracts.back")}</BackLink>
          <span className="text-xs text-muted font-mono truncate hidden sm:inline">
            {t("contracts.title")}
          </span>
        </div>
        <span className="flex items-center gap-1 text-xs text-fg-2 shrink-0">
          <FileCode2 size={14} aria-hidden="true" />
          {t("contracts.total", { n: contracts.length })}
        </span>
      </PageHeader>

      <main className="max-w-6xl mx-auto px-4 py-6 space-y-3">
        {loading && (
          <div className="py-12 flex justify-center">
            <Loading label={t("contracts.loading")} />
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

        {!loading && !error && contracts.length === 0 && (
          <div className="text-center text-sm text-muted py-12">{t("contracts.empty")}</div>
        )}

        {!loading &&
          !error &&
          contracts.map((contract) => (
            <Link
              key={contract.address}
              to={`/contracts/${contract.address}`}
              aria-label={t("contracts.view", { address: contract.address })}
              className="block bg-surface border border-border rounded-lg px-4 py-3 hover:border-accent hover:bg-surface-2 transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
            >
              <div className="flex items-center justify-between gap-3">
                <span className="text-accent font-mono text-sm truncate">
                  {orDash(contract.address)}
                </span>
                <span className="text-xs text-muted shrink-0">
                  {formatRelativeTime(contract.createdAt, i18n.language)}
                </span>
              </div>
              <div className="flex items-center justify-between gap-3 mt-1">
                <span className="text-xs text-muted truncate">
                  {t("contracts.creator")}:{" "}
                  <span className="font-mono">{orDash(contract.creator)}</span>
                </span>
                <span className="text-xs text-muted font-mono truncate max-w-[45%]">
                  {orDash(contract.codeHash)}
                </span>
              </div>
            </Link>
          ))}
      </main>
    </div>
  );
};

export default ContractsPage;
