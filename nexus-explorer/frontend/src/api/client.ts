import type { BlockInfo, TransactionInfo, AccountInfo, ChainStatus } from "../types";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:3000";

class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

async function request<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}

export const api = {
  // Blocks
  getBlocks: (limit = 20) => request<BlockInfo[]>(`/api/blocks?limit=${limit}`),
  getBlock: (height: number) => request<BlockInfo>(`/api/blocks/${height}`),

  // Transactions
  getTransactions: (limit = 20) => request<TransactionInfo[]>(`/api/tx?limit=${limit}`),
  getTransaction: (hash: string) => request<TransactionInfo>(`/api/tx/${hash}`),

  // Account
  getAccount: (address: string) => request<AccountInfo>(`/api/address/${address}`),

  // Chain status
  getStatus: () => request<ChainStatus>(`/api/node/status`),
};

export { ApiError };
