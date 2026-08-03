/**
 * NexusChain SDK 交易模块。
 *
 * 提供交易构建、签名、序列化和广播能力。
 * 支持 NEX 原生转账及合约调用。
 */

import type { RpcClient } from './rpc';
import type {
  Transaction,
  TransferParams,
  TransactionReceipt,
} from './types';

/** 交易管理器 */
export class TransactionManager {
  private readonly rpcClient: RpcClient;
  private readonly network: string;

  constructor(rpcClient: RpcClient, network: string) {
    this.rpcClient = rpcClient;
    this.network = network;
  }

  /**
   * 构建 NEX 原生转账交易。
   *
   * @param params 转账参数
   * @returns 未签名的交易对象
   */
  async buildTransfer(params: TransferParams): Promise<Transaction> {
    // TODO: 构建 nonce、gas 等参数
    const nonce = await this.rpcClient.call('nexus_getTransactionCount', [params.from, 'latest']);
    const gasPrice = await this.rpcClient.call('nexus_gasPrice', []);

    return {
      from: params.from,
      to: params.to,
      value: params.amount,
      token: params.token ?? 'NEX',
      nonce: nonce as string,
      gasPrice: gasPrice as string,
    };
  }

  /**
   * 构建合约调用交易。
   *
   * @param from             发送方地址
   * @param contractAddress  合约地址
   * @param data             调用数据（ABI 编码）
   * @param value            附带的 NEX 金额
   * @returns 未签名的交易对象
   */
  async buildContractCall(
    from: string,
    contractAddress: string,
    data: string,
    value?: string,
  ): Promise<Transaction> {
    // TODO: 构建合约调用交易
    throw new Error('Not yet implemented');
  }

  /**
   * 对交易进行签名。
   *
   * @param tx         交易对象
   * @param privateKey 签名私钥（十六进制）
   * @returns 已签名的交易序列化字符串
   */
  sign(tx: Transaction, privateKey: string): string {
    // TODO: 使用私钥签名交易
    throw new Error('Not yet implemented');
  }

  /**
   * 广播已签名的交易到网络。
   *
   * @param signedTx 已签名的交易序列化字符串
   * @returns 交易哈希
   */
  async broadcast(signedTx: string): Promise<string> {
    const result = await this.rpcClient.call('nexus_sendRawTransaction', [signedTx]);
    return result as string;
  }

  /**
   * 查询交易状态。
   *
   * @param txHash 交易哈希
   * @returns 交易回执
   */
  async getTransactionReceipt(txHash: string): Promise<TransactionReceipt | null> {
    const result = await this.rpcClient.call('nexus_getTransactionReceipt', [txHash]);
    return result as TransactionReceipt | null;
  }

  /**
   * 估算交易所需的 Gas。
   *
   * @param tx 交易对象
   * @returns Gas 估算值（十六进制）
   */
  async estimateGas(tx: Transaction): Promise<string> {
    const result = await this.rpcClient.call('nexus_estimateGas', [tx]);
    return result as string;
  }

  /**
   * 获取当前 Gas 价格。
   *
   * @returns Gas 价格（wei，十六进制）
   */
  async getGasPrice(): Promise<string> {
    const result = await this.rpcClient.call('nexus_gasPrice', []);
    return result as string;
  }
}
