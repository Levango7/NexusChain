/**
 * NexusChain SDK 主客户端。
 *
 * 统一入口，聚合钱包管理、交易构造/签名/广播、RPC 客户端等全部能力。
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
 *
 * const wallet = await client.wallet.create();
 * const balance = await client.wallet.getBalance(wallet.address);
 * ```
 */

import { RpcClient } from './rpc';
import { WalletManager } from './wallet';
import { TransactionManager } from './transaction';
import type { NexusChainConfig } from './types';

/**
 * NexusChain SDK 主客户端类。
 */
export class NexusChainClient {
  /** 客户端配置 */
  readonly config: NexusChainConfig;
  /** 底层 RPC 客户端 */
  readonly rpc: RpcClient;
  /** 钱包管理器 */
  readonly wallet: WalletManager;
  /** 交易管理器 */
  readonly transaction: TransactionManager;

  /**
   * 创建 NexusChainClient 实例。
   *
   * @param config 客户端配置
   */
  constructor(config: NexusChainConfig) {
    this.config = config;
    this.rpc = new RpcClient({
      rpcUrl: config.rpcUrl,
      timeout: config.timeout,
      apiKey: config.apiKey,
    });
    this.wallet = new WalletManager(this.rpc, config.network);
    this.transaction = new TransactionManager(this.rpc, config.network);
  }

  /**
   * 获取当前网络类型。
   *
   * @returns 网络名称
   */
  get network(): string {
    return this.config.network;
  }

  /**
   * 获取 RPC 地址。
   *
   * @returns RPC URL
   */
  get rpcUrl(): string {
    return this.config.rpcUrl;
  }

  /**
   * 查询当前区块高度。
   *
   * @returns 区块高度
   */
  async getBlockNumber(): Promise<number> {
    return this.rpc.getBlockNumber();
  }

  /**
   * 获取网络链 ID。
   *
   * @returns 链 ID
   */
  async getChainId(): Promise<number> {
    return this.rpc.getChainId();
  }
}
