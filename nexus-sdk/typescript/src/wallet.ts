/**
 * NexusChain SDK 钱包管理模块。
 *
 * 提供钱包创建、导入、余额查询等能力。
 * 所有余额以最小单位（wei）表示，NEX 为原生代币。
 */

import type { RpcClient } from './rpc';
import type { WalletInfo, TokenSymbol } from './types';

/** 钱包管理器 */
export class WalletManager {
  private readonly rpcClient: RpcClient;
  private readonly network: string;

  constructor(rpcClient: RpcClient, network: string) {
    this.rpcClient = rpcClient;
    this.network = network;
  }

  /**
   * 创建新钱包，生成新的密钥对。
   *
   * Not implemented: wallet key generation must be performed by the wallet-service
   * (nexus-wallet-service) which enforces KMS, key rotation, and audit policies.
   *
   * @returns 新创建的 WalletInfo
   * @throws  Error — 本方法未实现，应调用 wallet-service API。
   */
  async create(): Promise<WalletInfo> {
    throw new Error(
      'WalletManager.create not implemented: use wallet-service API instead. ' +
        'See nexus-wallet-service OpenAPI docs for the /api/v1/wallets endpoint.'
    );
  }

  /**
   * 从私钥导入钱包。
   *
   * @param privateKey 十六进制私钥
   * @returns 导入的 WalletInfo
   * @throws  Error — 本方法未实现，应调用 wallet-service API。
   */
  async fromPrivateKey(privateKey: string): Promise<WalletInfo> {
    throw new Error(
      'WalletManager.fromPrivateKey not implemented: use wallet-service API instead. ' +
        'See nexus-wallet-service OpenAPI docs for the /api/v1/wallets/import endpoint.'
    );
  }

  /**
   * 从助记词导入钱包。
   *
   * @param mnemonic BIP-39 助记词
   * @param path      派生路径（如 "m/44'/60'/0'/0/0"）
   * @returns 导入的 WalletInfo
   * @throws  Error — 本方法未实现，应调用 wallet-service API。
   */
  async fromMnemonic(mnemonic: string, path?: string): Promise<WalletInfo> {
    throw new Error(
      'WalletManager.fromMnemonic not implemented: use wallet-service API instead. ' +
        'See nexus-wallet-service OpenAPI docs for the /api/v1/wallets/import-mnemonic endpoint.'
    );
  }

  /**
   * 查询地址的 NEX 余额。
   *
   * 对齐 nexus-core：nexus_getBalance 返回 {"balance": "<decimal>"} 信封，需解包。
   *
   * @param address 钱包地址
   * @returns 余额（最小单位 wei）
   */
  async getBalance(address: string): Promise<string> {
    const result = (await this.rpcClient.call('nexus_getBalance', [address, 'latest'])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const obj = result as Record<string, unknown>;
      if (obj.balance !== undefined) {
        return obj.balance as string;
      }
    }
    return result as string;
  }

  /**
   * 查询地址的指定代币余额。
   *
   * @param address       钱包地址
   * @param tokenContract 代币合约地址
   * @returns 代币余额（最小单位）
   * @throws  Error — 本方法未实现，应调用 wallet-service API 或直接调用合约。
   */
  async getTokenBalance(address: string, tokenContract: string): Promise<string> {
    throw new Error(
      'WalletManager.getTokenBalance not implemented: use wallet-service API or call contract balanceOf directly. ' +
        'See nexus-wallet-service OpenAPI docs for the /api/v1/wallets/balance endpoint.'
    );
  }

  /**
   * 验证地址格式是否合法。
   *
   * @param address 待验证地址
   * @returns 是否合法
   * @throws  Error — 本方法未实现，应调用 wallet-service API 或本地校验。
   */
  validateAddress(address: string): boolean {
    throw new Error(
      'WalletManager.validateAddress not implemented: use wallet-service API or validate locally. ' +
        'See nexus-wallet-service OpenAPI docs for the /api/v1/wallets/validate-address endpoint.'
    );
  }
}
