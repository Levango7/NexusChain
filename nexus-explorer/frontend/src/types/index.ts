export interface BlockInfo {
  height: number;
  hash: string;
  timestamp: number;
  txCount: number;
  proposer: string;
  parentHash: string;
  difficulty: number;
  size: number;
}

export interface TransactionInfo {
  txHash: string;
  blockHeight: number;
  blockHash: string;
  from: string;
  to: string;
  amount: string;
  fee: number;
  type: number;
  typeName: string;
  nonce: number;
  status: "success" | "failed" | "pending";
  timestamp: number;
  payload: string | null;
}

export interface AccountInfo {
  address: string;
  publicKeyHash: string;
  balance: string;
  nonce: number;
  txCount: number;
}

export interface TxRecord {
  tx_hash: string;
  amount: number;
  height: number;
  from: string;
  to: string;
  datetime: string;
  type: "+" | "-";
}

export interface ChainStatus {
  height: number;
  peers: number;
  version: string;
  network: string;
  tps: number;
}

export interface PaginatedResponse<T> {
  data: T[];
  total: number;
  page: number;
  pageSize: number;
}
