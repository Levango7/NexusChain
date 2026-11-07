import React, { useState, useEffect } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api, ApiError } from "../api/client";
import type { ContractInfo } from "../types";
import { DetailPageLayout, Button } from "../components/ui";
import { displayErrorMessage } from "../utils/displayError";
import { orDash } from "../utils/value";
import { formatAbsoluteTime } from "../utils/time";

/** 代码折叠前的预览字符数（wasmCode 可达数百 KB，全量渲染会卡死页面）。 */
const CODE_PREVIEW_CHARS = 200;

/**
 * ContractPage — 合约详情（`/contracts/:addr`）。
 *
 * 展示字段与 `RpcContract`（core `nexus_getContract` 透传）逐字对齐：
 * address / creator / codeHash / wasmCode / createdAt，`abi` 可选。
 * core 当前不返回 abi，故 UI 在缺失时显式说明「节点未提供」，
 * 而不是渲染成空白区域（用户无法区分「无 ABI」与「加载失败」）。
 */
const ContractPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const { addr } = useParams<{ addr: string }>();
  const [contract, setContract] = useState<ContractInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showFullCode, setShowFullCode] = useState(false);
  /** 重试计数：自增即重跑加载（审计 B7）。 */
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // addr 为 undefined 时会请求 /api/contracts/undefined
    if (!addr) return;
    setLoading(true);
    (async () => {
      try {
        const data = await api.getContract(addr);
        setContract(data);
        // 后端可能以 200 + null 表示「查无此合约」（原实现同样处理）。必须显式置错误态：
        // 否则 loading=false、error=null、contract=null ⇒ DetailPageLayout 既不渲染
        // loading 也不渲染 error ⇒ 只剩一个 header 的空白页。
        setError(data ? null : t("contracts.notFound"));
      } catch (err) {
        console.error("API请求失败:", err);
        setContract(null);
        // 404 才是「合约不存在」；其余按真实原因提示（审计 B5：错误语义错配）
        if (err instanceof ApiError && err.status === 404) {
          setError(t("contracts.notFound"));
        } else {
          setError(displayErrorMessage(err, t, "contracts.loadFailed"));
        }
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addr, reloadKey]);

  if (loading || error || !contract) {
    return (
      <DetailPageLayout
        loading={loading}
        loadingLabel={t("contracts.loading")}
        error={error}
        onRetry={() => setReloadKey((k) => k + 1)}
        title={t("contracts.title")}
        backLabel={t("contracts.back")}
      />
    );
  }

  const code = contract.wasmCode ?? "";
  const truncated = code.length > CODE_PREVIEW_CHARS && !showFullCode;
  const shownCode = truncated ? code.slice(0, CODE_PREVIEW_CHARS) : code;
  const hasAbi = contract.abi !== undefined && contract.abi !== null;

  return (
    <DetailPageLayout
      loading={false}
      title={t("contracts.title")}
      backLabel={t("contracts.back")}
      mainClassName="max-w-4xl mx-auto px-4 py-6 space-y-6"
    >
      <div className="bg-surface border border-border rounded-lg p-5">
        <div className="text-xs text-muted mb-1">{t("contracts.address")}</div>
        <code className="text-sm text-fg break-all">{orDash(contract.address)}</code>

        <div className="grid grid-cols-1 gap-4 mt-4 pt-4 border-t border-border-soft sm:grid-cols-2">
          <div>
            <div className="text-xs text-muted">{t("contracts.creator")}</div>
            <code className="text-sm text-fg break-all">{orDash(contract.creator)}</code>
          </div>
          <div>
            <div className="text-xs text-muted">{t("contracts.createdAt")}</div>
            <div className="text-sm text-fg">
              {formatAbsoluteTime(contract.createdAt, i18n.language)}
            </div>
          </div>
          <div className="sm:col-span-2">
            <div className="text-xs text-muted">{t("contracts.codeHash")}</div>
            <code className="text-sm text-fg break-all">{orDash(contract.codeHash)}</code>
          </div>
        </div>
      </div>

      <div className="bg-surface border border-border rounded-lg p-5">
        <div className="flex items-center justify-between gap-3 mb-3">
          <h2 className="text-sm font-semibold text-fg-2">{t("contracts.code")}</h2>
          {code.length > CODE_PREVIEW_CHARS && (
            <Button variant="ghost" size="sm" onClick={() => setShowFullCode((v) => !v)}>
              {showFullCode ? t("contracts.codeCollapse") : t("contracts.codeExpand")}
            </Button>
          )}
        </div>
        {truncated && (
          <div className="text-xs text-muted mb-2">
            {t("contracts.codeTruncated", { len: code.length })}
          </div>
        )}
        <pre className="text-xs font-mono text-fg-2 bg-surface-2 border border-border-soft rounded-md p-3 overflow-x-auto whitespace-pre-wrap break-all">
          {orDash(shownCode)}
        </pre>
      </div>

      <div className="bg-surface border border-border rounded-lg p-5">
        <h2 className="text-sm font-semibold text-fg-2 mb-3">{t("contracts.abi")}</h2>
        {hasAbi ? (
          <pre className="text-xs font-mono text-fg-2 bg-surface-2 border border-border-soft rounded-md p-3 overflow-x-auto">
            {JSON.stringify(contract.abi, null, 2)}
          </pre>
        ) : (
          <div className="text-xs text-muted">{t("contracts.abiUnavailable")}</div>
        )}
      </div>
    </DetailPageLayout>
  );
};

export default ContractPage;
