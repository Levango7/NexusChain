/**
 * NexusChain SDK RPC 客户端。
 *
 * 封装 NexusChain 节点的 JSON-RPC 接口，提供底层网络通信能力。
 * 支持连接池管理、自动重连和批量请求。
 */

import axios, { AxiosInstance, AxiosRequestConfig } from 'axios';
import type {
  RpcRequest,
  RpcResponse,
  Block,
  NexusChainConfig,
} from './types';

/** RPC 客户端配置 */
export interface RpcClientOptions {
  rpcUrl: string;
  timeout?: number;
  apiKey?: string;
}

/**
 * NexusChain JSON-RPC 客户端。
 */
export class RpcClient {
  private readonly rpcUrl: string;
  private readonly timeout: number;
  private readonly apiKey?: string;
  private readonly httpClient: AxiosInstance;
  private requestId: number = 0;

  constructor(options: RpcClientOptions) {
    this.rpcUrl = options.rpcUrl;
    this.timeout = options.timeout ?? 30000;
    this.apiKey = options.apiKey;

    const config: AxiosRequestConfig = {
      baseURL: this.rpcUrl,
      timeout: this.timeout,
      headers: {
        'Content-Type': 'application/json',
        ...(this.apiKey ? { Authorization: `Bearer ${this.apiKey}` } : {}),
      },
    };
    this.httpClient = axios.create(config);
  }

  /**
   * 发送 JSON-RPC 请求。
   *
   * @param method RPC 方法名
   * @param params 参数列表
   * @returns 响应结果
   */
  async call(method: string, params: unknown[] = []): Promise<unknown> {
    const request: RpcRequest = {
      jsonrpc: '2.0',
      method,
      params,
      id: ++this.requestId,
    };

    const response = await this.httpClient.post<RpcResponse>('/', request);
    const data = response.data;

    if (data.error) {
      throw new Error(`RPC Error ${data.error.code}: ${data.error.message}`);
    }

    return data.result;
  }

  /**
   * 批量发送 JSON-RPC 请求。
   *
   * @param requests 请求列表
   * @returns 响应列表
   */
  async batchCall(requests: RpcRequest[]): Promise<RpcResponse[]> {
    const enrichedRequests = requests.map((req, index) => ({
      ...req,
      id: ++this.requestId + index,
    }));

    const response = await this.httpClient.post<RpcResponse[]>('/', enrichedRequests);
    return response.data;
  }

  /**
   * 查询当前区块高度。
   *
   * 兼容实现：nexus-core 未提供 nexus_blockNumber，改为调用
   * nexus_getLatestBlocks 取最新区块列表中的第一个区块高度。
   *
   * @returns 区块高度
   */
  async getBlockNumber(): Promise<number> {
    const result = (await this.call('nexus_getLatestBlocks', [1])) as unknown;
    if (Array.isArray(result)) {
      if (result.length === 0) return 0;
      const first = result[0] as Record<string, unknown> | string;
      if (typeof first === 'object' && first !== null) {
        const h = (first as Record<string, unknown>).height ?? (first as Record<string, unknown>).number;
        return typeof h === 'string' ? parseInt(h as string, 16) : Number(h);
      }
      return typeof first === 'string' ? parseInt(first, 16) : Number(first);
    }
    return parseInt(result as string, 16);
  }

  /**
   * 根据 hash 获取区块信息。
   *
   * 注意：nexus-core 当前未提供 nexus_getBlockByHash，保留接口以兼容旧 SDK 用户。
   * 实际应通过 nexus_getBlockByHeight 配合索引服务使用。
   *
   * @param blockHash 区块哈希
   * @returns 区块信息
   */
  async getBlockByHash(blockHash: string): Promise<Block | null> {
    void blockHash;
    throw new Error(
      'nexus_getBlockByHash not supported by nexus-core; use getBlockByNumber instead',
    );
  }

  /**
   * 根据区块号获取区块信息。
   *
   * 对齐 nexus-core：nexus_getBlockByNumber → nexus_getBlockByHeight。
   *
   * @param blockNumber 区块号
   * @returns 区块信息
   */
  async getBlockByNumber(blockNumber: number): Promise<Block | null> {
    return (await this.call('nexus_getBlockByHeight', [
      blockNumber,
      true,
    ])) as Block | null;
  }

  /**
   * 获取网络链 ID。
   *
   * 兼容实现：nexus-core 未提供 nexus_chainId，改为调用
   * nexus_getNodeStatus 从节点状态中获取 chainId 字段。
   *
   * @returns 链 ID
   */
  async getChainId(): Promise<number> {
    const result = (await this.call('nexus_getNodeStatus', [])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const obj = result as Record<string, unknown>;
      const cid = obj.chainId ?? obj.chain_id;
      if (cid !== undefined) {
        return typeof cid === 'string' ? parseInt(cid, 16) : Number(cid);
      }
    }
    return parseInt(result as string, 16);
  }

  /**
   * 获取当前 Gas 价格。
   *
   * 兼容实现：nexus-core 未提供 nexus_gasPrice，改为调用
   * nexus_getNodeStatus 从节点状态中获取 gasPrice 字段；若不存在则返回默认值 1 gwei。
   *
   * @returns Gas 价格（wei，十六进制）
   */
  async getGasPrice(): Promise<string> {
    const result = (await this.call('nexus_getNodeStatus', [])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const obj = result as Record<string, unknown>;
      if (obj.gasPrice !== undefined) {
        return obj.gasPrice as string;
      }
    }
    // 默认 1 gwei
    return '0x3b9aca00';
  }

  /**
   * 从配置创建 RpcClient 实例。
   *
   * @param config NexusChain 配置
   * @returns RpcClient 实例
   */
  static fromConfig(config: NexusChainConfig): RpcClient {
    return new RpcClient({
      rpcUrl: config.rpcUrl,
      timeout: config.timeout,
      apiKey: config.apiKey,
    });
  }
}
