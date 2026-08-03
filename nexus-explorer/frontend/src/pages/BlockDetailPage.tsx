import React, { useState, useEffect } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { api } from "../api/client";
import type { BlockInfo } from "../types";

const BlockDetailPage: React.FC = () => {
  const { height } = useParams<{ height: string }>();
  const navigate = useNavigate();
  const [block, setBlock] = useState<BlockInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const data = await api.getBlock(Number(height));
        setBlock(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Block not found");
      } finally {
        setLoading(false);
      }
    })();
  }, [height]);

  if (loading) return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-gray-400">Loading block #{height}...</div>;
  if (error) return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-red-400">{error}</div>;
  if (!block) return null;

  const rows: [string, React.ReactNode][] = [
    ["Height", <span className="font-mono text-indigo-400">{block.height}</span>],
    ["Hash", <code className="break-all text-xs">{block.hash}</code>],
    ["Parent Hash", <code className="break-all text-xs text-gray-400">{block.parentHash}</code>],
    ["Proposer", <code className="text-xs">{block.proposer}</code>],
    ["Timestamp", new Date(block.timestamp * 1000).toLocaleString()],
    ["Transactions", `${block.txCount}`],
    ["Size", `${block.size} bytes`],
    ["Difficulty", `${block.difficulty}`],
  ];

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100">
      <header className="border-b border-gray-800 bg-gray-900/80 backdrop-blur sticky top-0 z-10">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link to="/" className="text-indigo-400 hover:text-indigo-300 text-sm transition">&larr; Back</Link>
          <h1 className="text-sm font-semibold text-gray-300">Block <span className="text-indigo-400 font-mono">#{block.height}</span></h1>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-6">
        <div className="bg-gray-900 border border-gray-800 rounded-lg divide-y divide-gray-800">
          {rows.map(([label, value]) => (
            <div key={label} className="px-5 py-3 grid grid-cols-4 gap-2 text-sm">
              <span className="text-gray-500">{label}</span>
              <span className="col-span-3 text-gray-200">{value}</span>
            </div>
          ))}
        </div>
        <div className="mt-4 flex gap-2">
          <button onClick={() => navigate(`/block/${block.height - 1}`)} className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded hover:border-indigo-500/50 transition">&larr; Prev</button>
          <button onClick={() => navigate(`/block/${block.height + 1}`)} className="text-xs px-3 py-1.5 bg-gray-800 border border-gray-700 rounded hover:border-indigo-500/50 transition">Next &rarr;</button>
        </div>
      </main>
    </div>
  );
};

export default BlockDetailPage;
