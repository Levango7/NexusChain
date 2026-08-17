import React, { useState, useEffect, useCallback } from "react";
import { useNavigate, Link } from "react-router-dom";
import { Search, Settings as SettingsIcon } from "lucide-react";
import { api } from "../api/client";
import type { BlockInfo, TransactionInfo, ChainStatus } from "../types";
import { Loading } from "../components/ui";

/**
 * HomePage — 区块浏览器首页。
 *
 * 设计契约修复：
 *   - 颜色全部走 design tokens（bg / surface / fg / muted / accent / success）
 *   - 间距统一 max-w-6xl + px-4 + py-* 4 倍数
 *   - 圆角统一 rounded-md / rounded-lg
 *   - 内联 SVG 搜索图标替换为 lucide-react <Search />
 *   - Loading 文案替换为 <Loading /> 组件
 */
const HomePage: React.FC = () => {
  const navigate = useNavigate();
  const [blocks, setBlocks] = useState<BlockInfo[]>([]);
  const [transactions, setTransactions] = useState<TransactionInfo[]>([]);
  const [status, setStatus] = useState<ChainStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");

  const fetchData = useCallback(async () => {
    try {
      const [b, t, s] = await Promise.all([
        api.getBlocks(10),
        api.getTransactions(10),
        api.getStatus().catch(() => null),
      ]);
      setBlocks(b);
      setTransactions(t);
      if (s) setStatus(s);
    } catch {
      setBlocks([]);
      setTransactions([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
    const iv = setInterval(fetchData, 10000);
    return () => clearInterval(iv);
  }, [fetchData]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    const q = searchQuery.trim();
    if (!q) return;
    if (/^\d+$/.test(q)) navigate(`/block/${q}`);
    else if (q.length === 64) navigate(`/tx/${q}`);
    else navigate(`/address/${q}`);
  };

  const formatTime = (ts: number) => {
    const diff = Math.floor(Date.now() / 1000 - ts);
    if (diff < 60) return `${diff}s ago`;
    if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
    return `${Math.floor(diff / 3600)}h ago`;
  };

  return (
    <div className="min-h-screen bg-bg text-fg">
      <header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div
            className="flex items-center gap-2 cursor-pointer"
            role="button"
            tabIndex={0}
            aria-label="返回首页"
            onClick={() => navigate("/")}
            onKeyDown={(e) => {
              if (e.key === "Enter" || e.key === " ") {
                e.preventDefault();
                navigate("/");
              }
            }}
          >
            <span className="text-lg font-bold text-accent">NexusChain</span>
            <span className="text-xs text-muted font-mono">Explorer</span>
          </div>
          <div className="flex items-center gap-4">
            {status && (
              <div className="flex items-center gap-4 text-xs text-fg-2">
                <span>
                  Height:{" "}
                  <span className="text-fg font-mono">
                    {status.height.toLocaleString()}
                  </span>
                </span>
                <span>
                  Peers: <span className="text-fg">{status.peers}</span>
                </span>
                <span className="flex items-center gap-1">
                  <span className="w-1.5 h-1.5 rounded-full bg-success animate-pulse" />
                  Live
                </span>
              </div>
            )}
            <Link
              to="/settings"
              aria-label="设置"
              className="flex items-center gap-1 text-muted hover:text-accent text-xs transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
            >
              <SettingsIcon size={16} />
              <span className="hidden sm:inline">Settings</span>
            </Link>
          </div>
        </div>
      </header>

      <div className="max-w-6xl mx-auto px-4 pt-8 pb-4">
        <form onSubmit={handleSearch} className="relative">
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search by block height, tx hash, or address..."
            aria-label="搜索区块高度、交易哈希或地址"
            className="w-full bg-surface border border-border rounded-md px-4 py-3 text-sm text-fg placeholder-muted focus:outline-none focus:border-accent focus:ring-1 focus:ring-accent/30 transition-colors duration-base ease-standard"
          />
          <button
            type="submit"
            aria-label="搜索"
            className="absolute right-3 top-1/2 -translate-y-1/2 text-muted hover:text-accent transition-colors duration-base ease-standard focus:outline-none focus-visible:shadow-focus"
          >
            <Search size={20} />
          </button>
        </form>
      </div>

      <main className="max-w-6xl mx-auto px-4 py-4 grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section>
          <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-wide mb-3">
            Latest Blocks
          </h2>
          <div className="space-y-2">
            {loading && (
              <div className="py-8 flex justify-center">
                <Loading label="Loading..." />
              </div>
            )}
            {blocks.map((block) => (
              <div
                key={block.height}
                role="button"
                tabIndex={0}
                aria-label={`查看区块 #${block.height}`}
                onClick={() => navigate(`/block/${block.height}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    navigate(`/block/${block.height}`);
                  }
                }}
                className="bg-surface border border-border rounded-lg px-4 py-3 cursor-pointer hover:border-accent hover:bg-surface-2 transition-colors duration-base ease-standard group focus:outline-none focus-visible:shadow-focus"
              >
                <div className="flex items-center justify-between">
                  <span className="text-accent font-mono text-sm group-hover:text-accent-hover">
                    #{block.height}
                  </span>
                  <span className="text-xs text-muted">
                    {formatTime(block.timestamp)}
                  </span>
                </div>
                <div className="flex items-center justify-between mt-1">
                  <span className="text-xs text-muted font-mono truncate max-w-[200px]">
                    {block.hash}
                  </span>
                  <span className="text-xs text-fg-2">
                    {block.txCount} txns
                  </span>
                </div>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h2 className="text-sm font-semibold text-fg-2 uppercase tracking-wide mb-3">
            Latest Transactions
          </h2>
          <div className="space-y-2">
            {loading && (
              <div className="py-8 flex justify-center">
                <Loading label="Loading..." />
              </div>
            )}
            {transactions.map((tx) => (
              <div
                key={tx.txHash}
                role="button"
                tabIndex={0}
                aria-label={`查看交易 ${tx.txHash}`}
                onClick={() => navigate(`/tx/${tx.txHash}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    navigate(`/tx/${tx.txHash}`);
                  }
                }}
                className="bg-surface border border-border rounded-lg px-4 py-3 cursor-pointer hover:border-accent hover:bg-surface-2 transition-colors duration-base ease-standard group focus:outline-none focus-visible:shadow-focus"
              >
                <div className="flex items-center justify-between">
                  <span className="text-accent font-mono text-xs group-hover:text-accent-hover truncate max-w-[180px]">
                    {tx.txHash}
                  </span>
                  <span className="text-xs text-muted">
                    {formatTime(tx.timestamp)}
                  </span>
                </div>
                <div className="flex items-center justify-between mt-1">
                  <span className="text-xs text-muted">
                    {tx.from.slice(0, 8)}... → {tx.to.slice(0, 8)}...
                  </span>
                  <span className="text-xs font-medium text-success">
                    {tx.amount} NEX
                  </span>
                </div>
              </div>
            ))}
          </div>
        </section>
      </main>

      <footer className="border-t border-border mt-8">
        <div className="max-w-6xl mx-auto px-4 py-6 text-center text-xs text-muted">
          NexusChain Explorer — Blockchain Explorer for NEX Network
        </div>
      </footer>
    </div>
  );
};

export default HomePage;
