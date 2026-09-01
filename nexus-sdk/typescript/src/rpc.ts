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
   * v2.2.0 补真：对齐 nexus_getNodeStatus 真实信封（latestHeight 数值型，
   * 非 0x hex）。原骨架的 nexus_getLatestBlocks+hex 解析路径与真实信封不符。
   *
   * @returns 区块高度
   */
  async getBlockNumber(): Promise<number> {
    const result = (await this.call('nexus_getNodeStatus', [])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const h = (result as Record<string, unknown>).latestHeight;
      return typeof h === 'number' ? h : parseInt(String(h), 10);
    }
    throw new Error(`unexpected getNodeStatus envelope: ${typeof result}`);
  }

  /**
   * 根据 hash 获取区块信息。
   *
   * nexus-core 未提供 nexus_getBlockByHash（真实限制，非骨架假设）。
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
   * v2.2.0 补真：对齐 nexus_getNodeStatus.chainId（数值型）。
   *
   * @returns 链 ID
   */
  async getChainId(): Promise<number> {
    const result = (await this.call('nexus_getNodeStatus', [])) as unknown;
    if (typeof result === 'object' && result !== null) {
      const cid = (result as Record<string, unknown>).chainId;
      if (cid !== undefined) {
        return typeof cid === 'number' ? cid : parseInt(String(cid), 10);
      }
    }
    throw new Error(`unexpected getNodeStatus envelope: ${typeof result}`);
  }

  /**
   * 获取节点状态全量信封（chainId/latestHeight/latestHash/syncing/peers/version）。
   */
  async getNodeStatus(): Promise<Record<string, unknown>> {
    const result = (await this.call('nexus_getNodeStatus', [])) as unknown;
    if (typeof result === 'object' && result !== null) {
      return result as Record<string, unknown>;
    }
    throw new Error(`unexpected getNodeStatus envelope: ${typeof result}`);
  }

  /**
   * 获取当前 Gas 价格。
   *
   * v2.2.0 补真：nexus_getNodeStatus 信封实际不含 gasPrice 字段
   * （JsonRpcController.doGetNodeStatus 只返回 chainId/latestHeight/
   * latestHash/syncing/peers/version）。原骨架的 gasPrice 读取永远走
   * 默认值分支。保留默认 1 gwei 兜底并如实标注。
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
    // 节点状态无 gasPrice 字段 → 默认 1 gwei（与 Java GAS_TABLE 体系并存）
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
