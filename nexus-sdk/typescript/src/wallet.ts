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
   * @returns 新创建的 WalletInfo
   */
  async create(): Promise<WalletInfo> {
    // TODO: 生成 ECDSA 密钥对
    throw new Error('Not yet implemented');
  }

  /**
   * 从私钥导入钱包。
   *
   * @param privateKey 十六进制私钥
   * @returns 导入的 WalletInfo
   */
  async fromPrivateKey(privateKey: string): Promise<WalletInfo> {
    // TODO: 从私钥推导公钥和地址
    throw new Error('Not yet implemented');
  }

  /**
   * 从助记词导入钱包。
   *
   * @param mnemonic BIP-39 助记词
   * @param path      派生路径（如 "m/44'/60'/0'/0/0"）
   * @returns 导入的 WalletInfo
   */
  async fromMnemonic(mnemonic: string, path?: string): Promise<WalletInfo> {
    // TODO: 从助记词派生密钥对
    throw new Error('Not yet implemented');
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
   */
  async getTokenBalance(address: string, tokenContract: string): Promise<string> {
    // TODO: 调用合约 balanceOf 方法
    throw new Error('Not yet implemented');
  }

  /**
   * 验证地址格式是否合法。
   *
   * @param address 待验证地址
   * @returns 是否合法
   */
  validateAddress(address: string): boolean {
    // TODO: 地址格式校验
    throw new Error('Not yet implemented');
  }
}
