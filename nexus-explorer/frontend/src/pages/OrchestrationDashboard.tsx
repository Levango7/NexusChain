import React, { useState, useEffect, useCallback } from "react";

const API_BASE = import.meta.env.VITE_GATEWAY_BASE ?? "http://localhost:8080";

interface Payment {
  id: string;
  status: string;
  amount: number;
  currency: string;
  connector: string;
  transaction_hash: string | null;
  created_at: string;
  confirmed_at: string | null;
}

interface Connector {
  id: string;
  type: string;
  display_name: string;
  active: boolean;
  fee_bps: number;
  currencies: string[];
}

interface RoutingRule {
  id: string;
  name: string;
  strategy: string;
  connectors: string[];
  priority: number;
}

const OrchestrationDashboard: React.FC = () => {
  const [payments, setPayments] = useState<Payment[]>([]);
  const [connectors, setConnectors] = useState<Connector[]>([]);
  const [rules, setRules] = useState<RoutingRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState<"payments" | "connectors" | "rules">("payments");

  const fetchData = useCallback(async () => {
    try {
      const [pRes, cRes, rRes] = await Promise.all([
        fetch(`${API_BASE}/api/v1/payments?merchantId=1&limit=20`).then(r => r.json()).catch(() => ({ data: [] })),
        fetch(`${API_BASE}/api/v1/payments/connectors`).then(r => r.json()).catch(() => []),
        fetch(`${API_BASE}/api/v1/payments/routing-rules`).then(r => r.json()).catch(() => []),
      ]);
      setPayments(pRes.data || []);
      setConnectors(Array.isArray(cRes) ? cRes : []);
      setRules(Array.isArray(rRes) ? rRes : []);
    } catch { /* ignore */ } finally { setLoading(false); }
  }, []);

  useEffect(() => { fetchData(); const iv = setInterval(fetchData, 8000); return () => clearInterval(iv); }, [fetchData]);

  const statusColor = (s: string) => {
    switch (s) {
      case "SUCCEEDED": return "text-emerald-400";
      case "PROCESSING": return "text-amber-400";
      case "FAILED": return "text-red-400";
      default: return "text-gray-400";
    }
  };

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100">
      <header className="border-b border-gray-800 bg-gray-900/80 backdrop-blur sticky top-0 z-10">
        <div className="max-w-6xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <span className="text-lg font-bold text-indigo-400">NexusChain</span>
            <span className="text-xs text-gray-500 font-mono">Payment Orchestration</span>
          </div>
          <div className="flex gap-1">
            {(["payments", "connectors", "rules"] as const).map(t => (
              <button key={t} onClick={() => setTab(t)}
                className={`px-3 py-1.5 rounded text-xs font-medium transition ${tab === t ? "bg-indigo-600 text-white" : "text-gray-400 hover:text-gray-200"}`}>
                {t.charAt(0).toUpperCase() + t.slice(1)}
              </button>
            ))}
          </div>
        </div>
      </header>

      <main className="max-w-6xl mx-auto px-4 py-6">
        {loading && <div className="text-gray-500 text-center py-12">Loading...</div>}

        {!loading && tab === "payments" && (
          <div className="space-y-2">
            <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wide mb-3">Recent Payments</h2>
            {payments.length === 0 && <p className="text-gray-600 text-sm">No payments yet.</p>}
            {payments.map(p => (
              <div key={p.id} className="bg-gray-900 border border-gray-800 rounded-lg px-4 py-3 flex items-center justify-between">
                <div>
                  <span className="font-mono text-xs text-indigo-400">{p.id}</span>
                  <div className="text-xs text-gray-500 mt-0.5">{p.connector} · {p.created_at?.slice(0, 19)}</div>
                </div>
                <div className="text-right">
                  <span className="text-sm font-medium">{p.amount} {p.currency}</span>
                  <div className={`text-xs font-medium ${statusColor(p.status)}`}>{p.status}</div>
                </div>
              </div>
            ))}
          </div>
        )}

        {!loading && tab === "connectors" && (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wide col-span-full mb-1">Payment Connectors</h2>
            {connectors.map(c => (
              <div key={c.id} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
                <div className="flex items-center justify-between">
                  <span className="font-medium text-sm">{c.display_name}</span>
                  <span className={`w-2 h-2 rounded-full ${c.active ? "bg-emerald-400" : "bg-red-400"}`} />
                </div>
                <div className="text-xs text-gray-500 mt-1">ID: {c.id} · Type: {c.type} · Fee: {c.fee_bps} bps</div>
                <div className="text-xs text-gray-600 mt-0.5">Currencies: {c.currencies?.length ? c.currencies.join(", ") : "ALL"}</div>
              </div>
            ))}
          </div>
        )}

        {!loading && tab === "rules" && (
          <div className="space-y-2">
            <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wide mb-3">Routing Rules</h2>
            {rules.map(r => (
              <div key={r.id} className="bg-gray-900 border border-gray-800 rounded-lg px-4 py-3">
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium">{r.name}</span>
                  <span className="text-xs px-2 py-0.5 rounded bg-indigo-900/50 text-indigo-300">{r.strategy}</span>
                </div>
                <div className="text-xs text-gray-500 mt-1">
                  Connectors: {r.connectors?.join(" → ")} · Priority: {r.priority}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
};

export default OrchestrationDashboard;