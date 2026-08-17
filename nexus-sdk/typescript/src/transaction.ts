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
    // nexus-core 未提供 nexus_gasPrice，使用 nexus_getNodeStatus 兜底
    const gasPrice = await this.rpcClient.call('nexus_getNodeStatus', []);

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
   * 注意：nexus-core 当前 JSON-RPC 入口未直接暴露 nexus_sendRawTransaction，
   * 交易广播通过 P2P 协议或 wallet-service 完成。此处保留接口以兼容旧 SDK 用户，
   * 实际部署应通过 wallet-service HTTP 接口提交。
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
   * 兼容实现：nexus-core 未提供 nexus_getTransactionReceipt，
   * 改为调用 nexus_getTransactionByHash 返回交易详情。
   *
   * @param txHash 交易哈希
   * @returns 交易回执
   */
  async getTransactionReceipt(txHash: string): Promise<TransactionReceipt | null> {
    const result = await this.rpcClient.call('nexus_getTransactionByHash', [txHash]);
    return result as TransactionReceipt | null;
  }

  /**
   * 估算交易所需的 Gas。
   *
   * 注意：nexus-core 当前未提供 nexus_estimateGas，保留接口以兼容旧 SDK 用户。
   *
   * @param tx 交易对象
   * @returns Gas 估算值（十六进制）
   */
  async estimateGas(tx: Transaction): Promise<string> {
    void tx;
    throw new Error('nexus_estimateGas not supported by nexus-core');
  }

  /**
   * 获取当前 Gas 价格。
   *
   * 兼容实现：nexus-core 未提供 nexus_gasPrice，改为调用 nexus_getNodeStatus
   * 从节点状态中获取 gasPrice 字段；若不存在则返回默认值 1 gwei。
   *
   * @returns Gas 价格（wei，十六进制）
   */
  async getGasPrice(): Promise<string> {
    const result = (await this.rpcClient.call('nexus_getNodeStatus', [])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const obj = result as Record<string, unknown>;
      if (obj.gasPrice !== undefined) {
        return obj.gasPrice as string;
      }
    }
    // 默认 1 gwei
    return '0x3b9aca00';
  }
}
