import React, { useState, useEffect } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import { api } from "../api/client";
import type { AccountInfo } from "../types";

const AddressPage: React.FC = () => {
  const { addr } = useParams<{ addr: string }>();
  const navigate = useNavigate();
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

  if (loading) return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-gray-400">Loading address...</div>;
  if (error) return <div className="min-h-screen bg-gray-950 flex items-center justify-center text-red-400">{error}</div>;

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100">
      <header className="border-b border-gray-800 bg-gray-900/80 backdrop-blur sticky top-0 z-10">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center gap-4">
          <Link to="/" className="text-indigo-400 hover:text-indigo-300 text-sm transition">&larr; Back</Link>
          <h1 className="text-sm font-semibold text-gray-300">Address</h1>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-6 space-y-6">
        {/* Account Info */}
        <div className="bg-gray-900 border border-gray-800 rounded-lg p-5">
          <div className="text-xs text-gray-500 mb-1">Address</div>
          <code className="text-sm text-gray-200 break-all">{addr}</code>
          {account && (
            <div className="grid grid-cols-3 gap-4 mt-4 pt-4 border-t border-gray-800">
              <div>
                <div className="text-xs text-gray-500">Balance</div>
                <div className="text-lg font-semibold text-emerald-400 mt-1">{account.balance}</div>
              </div>
              <div>
                <div className="text-xs text-gray-500">Nonce</div>
                <div className="text-lg font-mono text-gray-200 mt-1">{account.nonce}</div>
              </div>
              <div>
                <div className="text-xs text-gray-500">Transactions</div>
                <div className="text-lg font-mono text-gray-200 mt-1">{account.txCount}</div>
              </div>
            </div>
          )}
        </div>

      </main>
    </div>
  );
};

export default AddressPage;
