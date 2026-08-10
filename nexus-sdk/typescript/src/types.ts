/**
 * NexusChain SDK 公共类型定义。
 *
 * 项目名：NexusChain，代币符号：NEX。
 */

/** 网络类型 */
export type Network = 'mainnet' | 'testnet';

/** 客户端配置 */
export interface NexusChainConfig {
  /** 网络类型 */
  network: Network;
  /** NexusChain 节点 RPC 地址 */
  rpcUrl: string;
  /** 请求超时时间（毫秒），默认 30000 */
  timeout?: number;
  /** API 密钥（可选，用于付费节点认证） */
  apiKey?: string;
}

/** 钱包信息 */
export interface WalletInfo {
  address: string;
  privateKey: string;
  publicKey: string;
}

/** 代币符号 */
export type TokenSymbol = 'NEX' | string;

/** 交易对象 */
export interface Transaction {
  from: string;
  to: string;
  value: string;
  gasLimit?: string;
  gasPrice?: string;
  nonce?: string;
  data?: string;
  token?: TokenSymbol;
}

/** 转账参数 */
export interface TransferParams {
  from: string;
  to: string;
  amount: string;
  token?: TokenSymbol;
}

/** 交易回执 */
export interface TransactionReceipt {
  transactionHash: string;
  blockHash: string;
  blockNumber: number;
  status: 'success' | 'failed';
  gasUsed: string;
  logs?: TransactionLog[];
}

/** 交易日志 */
export interface TransactionLog {
  address: string;
  topics: string[];
  data: string;
  logIndex: number;
}

/** 区块信息 */
export interface Block {
  number: number;
  hash: string;
  parentHash: string;
  timestamp: number;
  transactions: string[];
  gasUsed: string;
  gasLimit: string;
}

/** 余额证明（支付通道） */
export interface BalanceProof {
  channelId: string;
  balance: string;
  nonce: number;
  signature: string;
}

/** 支付通道信息 */
export interface ChannelInfo {
  channelId: string;
  sender: string;
  recipient: string;
  deposit: string;
  status: 'open' | 'closed' | 'pending' | 'disputed';
  openBlock: number;
}

/** 跨链交易状态 */
export interface BridgeStatus {
  txHash: string;
  status: 'pending' | 'confirmed' | 'completed' | 'failed';
  sourceChain: string;
  targetChain: string;
  confirmations: number;
}

/** JSON-RPC 请求 */
export interface RpcRequest {
  jsonrpc: '2.0';
  method: string;
  params: unknown[];
  id: number;
}

/** JSON-RPC 响应 */
export interface RpcResponse {
  jsonrpc: '2.0';
  id: number;
  result?: unknown;
  error?: {
    code: number;
    message: string;
  };
}
