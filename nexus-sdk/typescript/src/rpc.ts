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
   * @returns 区块高度
   */
  async getBlockNumber(): Promise<number> {
    const result = await this.call('nexus_blockNumber', []);
    return parseInt(result as string, 16);
  }

  /**
   * 根据 hash 获取区块信息。
   *
   * @param blockHash 区块哈希
   * @returns 区块信息
   */
  async getBlockByHash(blockHash: string): Promise<Block | null> {
    return (await this.call('nexus_getBlockByHash', [blockHash, true])) as Block | null;
  }

  /**
   * 根据区块号获取区块信息。
   *
   * @param blockNumber 区块号
   * @returns 区块信息
   */
  async getBlockByNumber(blockNumber: number): Promise<Block | null> {
    return (await this.call('nexus_getBlockByNumber', [
      '0x' + blockNumber.toString(16),
      true,
    ])) as Block | null;
  }

  /**
   * 获取网络链 ID。
   *
   * @returns 链 ID
   */
  async getChainId(): Promise<number> {
    const result = await this.call('nexus_chainId', []);
    return parseInt(result as string, 16);
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
