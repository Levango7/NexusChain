/**
 * NexusChain SDK 入口模块。
 *
 * 统一多语言 SDK 的 TypeScript/JavaScript 实现。
 * 代币符号：NEX。
 *
 * @example
 * ```typescript
 * import { NexusChainClient } from '@nexus/sdk';
 *
 * const client = new NexusChainClient({
 *   network: 'mainnet',
 *   rpcUrl: 'https://rpc.nexus.network',
 * });
 * ```
 */

export { NexusChainClient } from './client';
export { RpcClient } from './rpc';
export { WalletManager } from './wallet';
export { TransactionManager } from './transaction';

export type {
  NexusChainConfig,
  Network,
  WalletInfo,
  TokenSymbol,
  Transaction,
  TransferParams,
  TransactionReceipt,
  TransactionLog,
  Block,
  BalanceProof,
  ChannelInfo,
  BridgeStatus,
  RpcRequest,
  RpcResponse,
} from './types';
