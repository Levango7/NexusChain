import React, { useState, useEffect } from "react";
import { useParams, Link } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { api } from "../api/client";
import type { AccountInfo } from "../types";
import { Loading } from "../components/ui";

/**
 * AddressPage — 地址详情页。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens
 *   - &larr; 字符替换为 lucide-react <ArrowLeft />
 *   - Loading 文案替换为 <Loading /> 组件
 *   - 间距 / 圆角 / 字体统一 token
 */
const AddressPage: React.FC = () => {
  const { addr } = useParams<{ addr: string }>();
  const [account, setAccount] = useState<AccountInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const acc = await api.getAccount(addr!).catch(() => null);
        setAccount(acc);
        if (!acc) setError("Address not found");
      } catch (err) {
        setError(err instanceof Error ? err.message : "Failed to load address");
      } finally {
        setLoading(false);
      }
    })();
  }, [addr]);

  if (loading)
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center">
        <Loading label="Loading address..." />
      </div>
    );
  if (error)
    return (
      <div className="min-h-screen bg-bg flex items-center justify-center text-danger">
        {error}
      </div>
    );

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link
            to="/"
            className="flex items-center gap-1 text-accent hover:text-accent-hover text-sm transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
          >
            <ArrowLeft size={14} />
            Back
          </Link>
          <h1 className="text-sm font-semibold text-fg-2">Address</h1>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-6 space-y-6">
        {/* Account Info */}
        <div className="bg-surface border border-border rounded-lg p-5">
          <div className="text-xs text-muted mb-1">Address</div>
          <code className="text-sm text-fg break-all">{addr}</code>
          {account && (
            <div className="grid grid-cols-3 gap-4 mt-4 pt-4 border-t border-border-soft">
              <div>
                <div className="text-xs text-muted">Balance</div>
                <div className="text-lg font-semibold text-success mt-1">
                  {account.balance}
                </div>
              </div>
              <div>
                <div className="text-xs text-muted">Nonce</div>
                <div className="text-lg font-mono text-fg mt-1">
                  {account.nonce}
                </div>
              </div>
              <div>
                <div className="text-xs text-muted">Transactions</div>
                <div className="text-lg font-mono text-fg mt-1">
                  {account.txCount}
                </div>
              </div>
            </div>
          )}
        </div>
      </main>
    </div>
  );
};

export default AddressPage;
