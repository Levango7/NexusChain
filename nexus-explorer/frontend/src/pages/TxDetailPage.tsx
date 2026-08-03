import React, { useState, useEffect } from "react";
import { useParams, Link } from "react-router-dom";
import { api } from "../api/client";
import type { TransactionInfo } from "../types";

const TxDetailPage: React.FC = () => {
  const { hash } = useParams<{ hash: string }>();
  const [tx, setTx] = useState<TransactionInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const data = await api.getTransaction(hash!);
        setTx(data);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Transaction not found");
      } finally {
        setLoading(false);
      }
    })();
  }, [hash]);

  if (loading) return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-gray-400">Loading transaction...</div>;
  if (error) return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-red-400">{error}</div>;
  if (!tx) return null;

  const statusColor = tx.status === "success" ? "text-emerald-400" : tx.status === "failed" ? "text-red-400" : "text-yellow-400";

  const rows: [string, React.ReactNode][] = [
    ["Tx Hash", <code className="break-all text-xs">{tx.txHash}</code>],
    ["Status", <span className={`font-medium ${statusColor}`}>{tx.status.toUpperCase()}</span>],
    ["Block", <Link to={`/block/${tx.blockHeight}`} className="text-indigo-400 hover:underline font-mono">#{tx.blockHeight}</Link>],
    ["Type", <span className="text-gray-300">{tx.typeName || `Type ${tx.type}`}</span>],
    ["From", <Link to={`/address/${tx.from}`} className="text-indigo-400 hover:underline break-all text-xs">{tx.from}</Link>],
    ["To", <Link to={`/address/${tx.to}`} className="text-indigo-400 hover:underline break-all text-xs">{tx.to}</Link>],
    ["Amount", <span className="text-emerald-400 font-medium">{tx.amount} NEX</span>],
    ["Fee", <span className="text-gray-300">{tx.fee} NEX</span>],
    ["Nonce", <span className="font-mono text-gray-300">{tx.nonce}</span>],
    ["Timestamp", new Date(tx.timestamp * 1000).toLocaleString()],
  ];

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100">
      <header className="border-b border-gray-800 bg-gray-900/80 backdrop-blur sticky top-0 z-10">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link to="/" className="text-indigo-400 hover:text-indigo-300 text-sm transition">&larr; Back</Link>
          <h1 className="text-sm font-semibold text-gray-300">Transaction Detail</h1>
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
      </main>
    </div>
  );
};

export default TxDetailPage;
