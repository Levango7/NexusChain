import React, { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { api } from "../api/client";
import type { BlockInfo, TransactionInfo, ChainStatus } from "../types";

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
    <div className="min-h-screen bg-gray-950 text-gray-100">
      <header className="border-b border-gray-800 bg-gray-900/80 backdrop-blur sticky top-0 z-10">
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
            <span className="text-lg font-bold text-indigo-400">NexusChain</span>
            <span className="text-xs text-gray-500 font-mono">Explorer</span>
          </div>
          {status && (
            <div className="flex items-center gap-4 text-xs text-gray-400">
              <span>Height: <span className="text-gray-200 font-mono">{status.height.toLocaleString()}</span></span>
              <span>Peers: <span className="text-gray-200">{status.peers}</span></span>
              <span className="flex items-center gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
                Live
              </span>
            </div>
          )}
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
            className="w-full bg-gray-900 border border-gray-700 rounded-lg px-4 py-3 text-sm text-gray-100 placeholder-gray-500 focus:outline-none focus:border-indigo-500 focus:ring-1 focus:ring-indigo-500/30 transition"
          />
          <button
            type="submit"
            aria-label="搜索"
            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-indigo-400 transition"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
          </button>
        </form>
      </div>

      <main className="max-w-6xl mx-auto px-4 py-4 grid grid-cols-1 lg:grid-cols-2 gap-6">
        <section>
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wide mb-3">Latest Blocks</h2>
          <div className="space-y-2">
            {loading && <div className="text-gray-500 text-sm py-8 text-center">Loading...</div>}
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
                className="bg-gray-900 border border-gray-800 rounded-lg px-4 py-3 cursor-pointer hover:border-indigo-500/50 hover:bg-gray-800/50 transition group"
              >
                <div className="flex items-center justify-between">
                  <span className="text-indigo-400 font-mono text-sm group-hover:text-indigo-300">#{block.height}</span>
                  <span className="text-xs text-gray-500">{formatTime(block.timestamp)}</span>
                </div>
                <div className="flex items-center justify-between mt-1">
                  <span className="text-xs text-gray-500 font-mono truncate max-w-[200px]">{block.hash}</span>
                  <span className="text-xs text-gray-400">{block.txCount} txns</span>
                </div>
              </div>
            ))}
          </div>
        </section>

        <section>
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wide mb-3">Latest Transactions</h2>
          <div className="space-y-2">
            {loading && <div className="text-gray-500 text-sm py-8 text-center">Loading...</div>}
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
                className="bg-gray-900 border border-gray-800 rounded-lg px-4 py-3 cursor-pointer hover:border-indigo-500/50 hover:bg-gray-800/50 transition group"
              >
                <div className="flex items-center justify-between">
                  <span className="text-indigo-400 font-mono text-xs group-hover:text-indigo-300 truncate max-w-[180px]">{tx.txHash}</span>
                  <span className="text-xs text-gray-500">{formatTime(tx.timestamp)}</span>
                </div>
                <div className="flex items-center justify-between mt-1">
                  <span className="text-xs text-gray-500">{tx.from.slice(0, 8)}... → {tx.to.slice(0, 8)}...</span>
                  <span className="text-xs font-medium text-emerald-400">{tx.amount} NEX</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      </main>

      <footer className="border-t border-gray-800 mt-8">
        <div className="max-w-6xl mx-auto px-4 py-6 text-center text-xs text-gray-600">
          NexusChain Explorer — Blockchain Explorer for NEX Network
        </div>
      </footer>
    </div>
  );
};

export default HomePage;