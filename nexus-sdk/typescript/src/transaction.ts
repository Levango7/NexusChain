/**
 * NexusChain SDK 交易模块（v2.2.0 补真，2026-09-01）。
 *
 * 构建转账（nonce 实时取链 {"count":N} 信封）、查询确认交易、跨链桥视图。
 * 签名/广播不是本模块职责——走 wallet-service（WalletManager.submitTransfer）。
 * 原骨架的 broadcast(nexus_sendRawTransaction) 调用不存在的 RPC 方法，
 * v2.2.0 移除并以 wallet-service 路径替代。
 */

import type { RpcClient } from './rpc';
import type { Transaction, TransferParams } from './types';
import { validateAddress } from './address';

/** 交易管理器 */
export class TransactionManager {
  private readonly rpcClient: RpcClient;
  private readonly network: string;

  constructor(rpcClient: RpcClient, network: string) {
    this.rpcClient = rpcClient;
    this.network = network;
  }

  /**
   * 构建 NEX 原生转账（nonce 实时取链；本地校验地址格式）。
   */
  async buildTransfer(params: TransferParams): Promise<Transaction> {
    if (!validateAddress(params.from)) throw new Error(`invalid from address: ${params.from}`);
    if (!validateAddress(params.to)) throw new Error(`invalid to address: ${params.to}`);
    const nonce = await this.rpcClient.call('nexus_getTransactionCount', [params.from]);
    let nonceValue: string;
    if (typeof nonce === 'object' && nonce !== null) {
      const c = (nonce as Record<string, unknown>).count;
      if (c === undefined) throw new Error('unexpected count envelope from getTransactionCount');
      nonceValue = String(c);
    } else {
      nonceValue = String(nonce);
    }
    return {
      from: params.from,
      to: params.to,
      value: params.amount,
      token: params.token ?? 'NEX',
      nonce: nonceValue,
    };
  }

  /**
   * 查确认交易（nexus_getTransactionByHash）。
   *
   * 注意这不是回执：core RPC 只返回已上链交易（status 恒 "success"）。
   */
  async getTransactionByHash(txHash: string): Promise<Record<string, unknown> | null> {
    const result = await this.rpcClient.call('nexus_getTransactionByHash', [txHash]);
    if (typeof result === 'object' && result !== null) {
      return result as Record<string, unknown>;
    }
    return null;
  }

  /** 最新交易列表（nexus_getLatestTransactions，服务端夹逼 1..100）。 */
  async getLatestTransactions(limit = 20): Promise<Record<string, unknown>[]> {
    const result = await this.rpcClient.call('nexus_getLatestTransactions', [limit]);
    if (Array.isArray(result)) return result as Record<string, unknown>[];
    throw new Error(`unexpected list envelope: ${typeof result}`);
  }
}

/** 跨链桥查询（nexus_getCrossChainTransactions：近 200 区块 BRIDGE_* 推导）。 */
export class BridgeManager {
  private readonly rpcClient: RpcClient;

  constructor(rpcClient: RpcClient) {
    this.rpcClient = rpcClient;
  }

  /** 跨链交易列表。statusFilter 可选（服务端按 payload status 字段匹配）。 */
  async list(limit = 20, statusFilter?: string): Promise<Record<string, unknown>[]> {
    const params: unknown[] = [limit];
    if (statusFilter) params.push(statusFilter);
    const result = await this.rpcClient.call('nexus_getCrossChainTransactions', params);
    if (Array.isArray(result)) return result as Record<string, unknown>[];
    throw new Error(`unexpected cross-chain envelope: ${typeof result}`);
  }
}
