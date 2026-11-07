import React, { useState, useEffect } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api, ApiError } from "../api/client";
import type { AccountInfo } from "../types";
import { DetailPageLayout } from "../components/ui";
import { displayErrorMessage } from "../utils/displayError";
import { orDash } from "../utils/value";

/**
 * AddressPage — 地址详情页。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens
 *   - &larr; 字符替换为 lucide-react <ArrowLeft />（经 DetailPageLayout 统一）
 *   - Loading 文案替换为 <Loading /> 组件
 *   - 间距 / 圆角 / 字体统一 token
 *   - P1: 复用 DetailPageLayout 提取的 header + loading + error 骨架
 */
const AddressPage: React.FC = () => {
  const { t } = useTranslation();
  const { addr } = useParams<{ addr: string }>();
  const [account, setAccount] = useState<AccountInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** 重试计数：自增即重跑加载（审计 B7）。 */
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // addr 为 undefined 时 api.getAccount(addr!) 会崩溃
    if (!addr) return;
    setLoading(true);
    (async () => {
      try {
        const acc = await api.getAccount(addr);
        setAccount(acc);
        // 200 + null 表示「查无此地址」（原实现同样处理）。必须显式置错误态，
        // 否则 loading/error/account 三者全空 ⇒ 只剩 header 的空白页。
        setError(acc ? null : t("address.notFound"));
      } catch (err) {
        console.error("API请求失败:", err);
        setAccount(null);
        // 只有 404 才叫「地址不存在」。此前所有异常都被内层 .catch 吞成 null、
        // 统一报 t("address.notFound") —— 后端没起来时用户会以为是自己地址写错了。
        // 现按真实原因分流（审计 B5 同族：错误语义错配）。
        if (err instanceof ApiError && err.status === 404) {
          setError(t("address.notFound"));
        } else {
          setError(displayErrorMessage(err, t, "address.loadFailed"));
        }
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addr, reloadKey]);

  return (
    <DetailPageLayout
      loading={loading}
      loadingLabel={t("address.loading")}
      error={error}
      onRetry={() => setReloadKey((k) => k + 1)}
      title={t("address.title")}
      backLabel={t("address.back")}
      mainClassName="max-w-4xl mx-auto px-4 py-6 space-y-6"
    >
      {/* Account Info */}
      <div className="bg-surface border border-border rounded-lg p-5">
        <div className="text-xs text-muted mb-1">{t("address.address")}</div>
        <code className="text-sm text-fg break-all">{addr}</code>
        {account && (
          // 响应式（2026-09-16 审查 P1）：窄屏单列堆叠，≥sm 起两列。
          // 已移除 nonce 展示 —— 后端 GET /api/address/:addr 只返回
          // { address, balance, txCount }，nonce 此前恒为 undefined（渲染为空白）。
          <div className="grid grid-cols-1 gap-4 mt-4 pt-4 border-t border-border-soft sm:grid-cols-2">
            <div>
              <div className="text-xs text-muted">{t("address.balance")}</div>
              <div className="text-lg font-semibold text-success mt-1">
                {orDash(account.balance)}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted">{t("address.transactions")}</div>
              <div className="text-lg font-mono text-fg mt-1">{orDash(account.txCount)}</div>
            </div>
          </div>
        )}
      </div>
    </DetailPageLayout>
  );
};

export default AddressPage;
