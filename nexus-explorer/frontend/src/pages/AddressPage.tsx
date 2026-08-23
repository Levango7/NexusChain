import React, { useState, useEffect } from "react";
import { useParams } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { api } from "../api/client";
import type { AccountInfo } from "../types";
import { DetailPageLayout } from "../components/ui";

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

  useEffect(() => {
    // addr 为 undefined 时 api.getAccount(addr!) 会崩溃
    if (!addr) return;
    (async () => {
      try {
        const acc = await api.getAccount(addr).catch(err => { console.error('API请求失败:', err); return null; });
        setAccount(acc);
        if (!acc) setError(t("address.notFound"));
      } catch (err) {
        setError(err instanceof Error ? err.message : t("address.loadFailed"));
      } finally {
        setLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [addr]);

  return (
    <DetailPageLayout
      loading={loading}
      loadingLabel={t("address.loading")}
      error={error}
      title={t("address.title")}
      backLabel={t("address.back")}
      mainClassName="max-w-4xl mx-auto px-4 py-6 space-y-6"
    >
      {/* Account Info */}
      <div className="bg-surface border border-border rounded-lg p-5">
        <div className="text-xs text-muted mb-1">{t("address.address")}</div>
        <code className="text-sm text-fg break-all">{addr}</code>
        {account && (
          <div className="grid grid-cols-3 gap-4 mt-4 pt-4 border-t border-border-soft">
            <div>
              <div className="text-xs text-muted">{t("address.balance")}</div>
              <div className="text-lg font-semibold text-success mt-1">
                {account.balance}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted">{t("address.nonce")}</div>
              <div className="text-lg font-mono text-fg mt-1">
                {account.nonce}
              </div>
            </div>
            <div>
              <div className="text-xs text-muted">{t("address.transactions")}</div>
              <div className="text-lg font-mono text-fg mt-1">
                {account.txCount}
              </div>
            </div>
          </div>
        )}
      </div>
    </DetailPageLayout>
  );
};

export default AddressPage;
