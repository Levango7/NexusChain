/**
 * NexusChain SDK 钱包管理模块（v2.2.0 补真，2026-09-01）。
 *
 * 查询走 nexus-core JSON-RPC 真实信封；交易提交走 nexus-wallet-service
 * HTTP（core 无 nexus_sendRawTransaction——签名与密钥管控是 wallet-service
 * 的架构职责，SDK 不持私钥）。地址校验为纯本地实现（Base58 + 25 字节 +
 * keccak 双哈希校验尾，对齐 Java KeystoreAction.verifyAddress）。
 */

import type { RpcClient } from './rpc';
import type { WalletInfo } from './types';
import { validateAddress } from './address';
import axios from 'axios';

/** 钱包管理器 */
export class WalletManager {
  private readonly rpcClient: RpcClient;
  private readonly network: string;
  private readonly walletServiceUrl?: string;
  private readonly apiKey?: string;

  constructor(
    rpcClient: RpcClient,
    network: string,
    walletServiceUrl?: string,
    apiKey?: string,
  ) {
    this.rpcClient = rpcClient;
    this.network = network;
    this.walletServiceUrl = walletServiceUrl;
    this.apiKey = apiKey;
  }

  /**
   * 创建新钱包。
   *
   * 架构决策（非缺陷）：密钥生成由 wallet-service 执行（KMS、轮换、审计
   * 策略集中管控），SDK 不在客户端生成私钥。调用 wallet-service 的
   * /api/v1/wallets 端点。
   */
  async create(): Promise<WalletInfo> {
    throw new Error(
      'WalletManager.create: key generation is wallet-service\'s domain ' +
        '(KMS/rotation/audit). POST /api/v1/wallets on nexus-wallet-service.',
    );
  }

  /** NEX 余额（最小单位）。信封：nexus_getBalance → {"balance":"<decimal>"}。 */
  async getBalance(address: string): Promise<string> {
    const result = (await this.rpcClient.call('nexus_getBalance', [address, 'latest'])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const obj = result as Record<string, unknown>;
      if (obj.balance !== undefined) return String(obj.balance);
    }
    return String(result);
  }

  /** 下一 nonce。信封：nexus_getTransactionCount → {"count":N}。 */
  async getNonce(address: string): Promise<number> {
    const result = (await this.rpcClient.call('nexus_getTransactionCount', [address])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const c = (result as Record<string, unknown>).count;
      if (c !== undefined) return Number(c);
    }
    throw new Error(`unexpected count envelope: ${typeof result}`);
  }

  /** 按地址查交易（nexus_getTransactionsByAddress → dict[]）。 */
  async getTransactionsByAddress(address: string, limit = 20): Promise<Record<string, unknown>[]> {
    const result = (await this.rpcClient.call('nexus_getTransactionsByAddress', [address, limit])) as unknown;
    if (Array.isArray(result)) return result as Record<string, unknown>[];
    throw new Error(`unexpected transaction list envelope: ${typeof result}`);
  }

  /** 本地地址校验（Base58 + 25 字节 + keccak 双哈希校验尾，不联网）。 */
  validateAddress(address: string): boolean {
    return validateAddress(address);
  }

  /**
   * 通过 wallet-service 签名并提交转账，返回交易哈希。
   *
   * 需要 NexusChainConfig.walletServiceUrl；本地先做地址格式校验。
   */
  async submitTransfer(from: string, to: string, amount: string): Promise<string> {
    if (!this.walletServiceUrl) {
      throw new Error(
        'walletServiceUrl is required for submitTransfer ' +
          '(submission goes through nexus-wallet-service, not core JSON-RPC)',
      );
    }
    if (!validateAddress(from)) throw new Error(`invalid from address: ${from}`);
    if (!validateAddress(to)) throw new Error(`invalid to address: ${to}`);

    const resp = await axios.post(
      `${this.walletServiceUrl.replace(/\/$/, '')}/api/v1/transfers`,
      { from, to, amount, token: 'NEX' },
      {
        headers: {
          'Content-Type': 'application/json',
          ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
        },
      },
    );
    const body = resp.data as Record<string, unknown>;
    const candidates = [
      body.txHash,
      body.hash,
      (body.data as Record<string, unknown> | undefined)?.txHash,
    ];
    for (const c of candidates) {
      if (typeof c === 'string' && c) return c;
    }
    throw new Error(`wallet-service response missing tx hash: ${JSON.stringify(body)}`);
  }
}
