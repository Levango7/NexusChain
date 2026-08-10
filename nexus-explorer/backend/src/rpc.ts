/**
 * NexusChain Explorer — RPC 客户端
 *
 * 封装与 nexus-core 节点的 JSON-RPC 通信逻辑。
 * 提供区块、交易、地址、合约、节点状态、跨链交易等查询方法。
 */

/** 区块数据 */
export interface RpcBlock {
  height: number;
  hash: string;
  parentHash: string;
  timestamp: number;
  txCount: number;
  proposer: string;
  transactions: string[];
}

/** 交易数据 */
export interface RpcTransaction {
  txHash: string;
  blockHeight: number;
  from: string;
  to: string;
  amount: string;
  status: 'success' | 'failed' | 'pending';
  timestamp: number;
  data?: string;
}

/** 地址信息 */
export interface RpcAddressInfo {
  address: string;
  balance: string;
  nonce: number;
}

/** 合约信息 */
export interface RpcContract {
  address: string;
  creator: string;
  codeHash: string;
  wasmCode: string;
  createdAt: number;
  abi?: unknown;
}

/** 节点状态 */
export interface RpcNodeStatus {
  chainId: number;
  latestHeight: number;
  latestHash: string;
  syncing: boolean;
  peers: number;
  version: string;
}

/** 跨链交易 */
export interface RpcCrossChainTx {
  txId: string;
  sourceChain: string;
  targetChain: string;
  amount: string;
  status: 'pending' | 'confirmed' | 'failed';
  timestamp: number;
  from: string;
  to: string;
}

/** JSON-RPC 请求体 */
interface JsonRpcRequest {
  jsonrpc: '2.0';
  id: number;
  method: string;
  params: unknown[];
}

/** JSON-RPC 响应体 */
interface JsonRpcResponse<T> {
  jsonrpc: '2.0';
  id: number;
  result?: T;
  error?: { code: number; message: string; data?: unknown };
}

/**
 * NexusChainRpcClient — nexus-core 节点 RPC 客户端
 *
 * 通过 JSON-RPC 2.0 协议与节点通信，提供类型化的查询接口。
 */
export class NexusChainRpcClient {
  private readonly url: string;
  private requestId: number = 0;

  constructor(url: string) {
    this.url = url;
  }

  /**
   * 发送 JSON-RPC 请求
   * @param method RPC 方法名
   * @param params 方法参数
   * @returns 响应结果
   */
  private async call<T>(method: string, params: unknown[] = []): Promise<T> {
    const reqBody: JsonRpcRequest = {
      jsonrpc: '2.0',
      id: ++this.requestId,
      method,
      params,
    };

    const res = await fetch(this.url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(reqBody),
    });

    if (!res.ok) {
      throw new Error(`RPC HTTP 错误: ${res.status} ${res.statusText}`);
    }

    const data: JsonRpcResponse<T> = await res.json();

    if (data.error) {
      throw new Error(`RPC 错误 [${data.error.code}]: ${data.error.message}`);
    }

    if (data.result === undefined) {
      throw new Error('RPC 返回结果为空');
    }

    return data.result;
  }

  /**
   * 获取最新区块列表
   * @param limit 返回数量上限
   */
  async getLatestBlocks(limit: number = 20): Promise<RpcBlock[]> {
    return this.call<RpcBlock[]>('nexus_getLatestBlocks', [limit]);
  }

  /**
   * 按高度获取区块详情
   */
  async getBlockByHeight(height: number): Promise<RpcBlock | null> {
    return this.call<RpcBlock | null>('nexus_getBlockByHeight', [height]);
  }

  /**
   * 获取最新交易列表
   */
  async getLatestTransactions(limit: number = 20): Promise<RpcTransaction[]> {
    return this.call<RpcTransaction[]>('nexus_getLatestTransactions', [limit]);
  }

  /**
   * 按哈希获取交易详情
   */
  async getTransactionByHash(hash: string): Promise<RpcTransaction | null> {
    return this.call<RpcTransaction | null>('nexus_getTransactionByHash', [hash]);
  }

  /**
   * 按地址获取相关交易列表
   */
  async getTransactionsByAddress(
    address: string,
    limit: number = 20
  ): Promise<RpcTransaction[]> {
    return this.call<RpcTransaction[]>('nexus_getTransactionsByAddress', [
      address,
      limit,
    ]);
  }

  /**
   * 获取地址 NEX 余额
   */
  async getBalance(address: string): Promise<string> {
    const result = await this.call<{ balance: string }>('nexus_getBalance', [
      address,
    ]);
    return result.balance;
  }

  /**
   * 获取地址交易计数
   */
  async getTransactionCount(address: string): Promise<number> {
    const result = await this.call<{ count: number }>(
      'nexus_getTransactionCount',
      [address]
    );
    return result.count;
  }

  /**
   * 获取已部署合约列表
   */
  async getContractList(): Promise<RpcContract[]> {
    return this.call<RpcContract[]>('nexus_getContractList', []);
  }

  /**
   * 获取指定合约详情
   */
  async getContract(address: string): Promise<RpcContract | null> {
    return this.call<RpcContract | null>('nexus_getContract', [address]);
  }

  /**
   * 获取节点状态信息
   */
  async getNodeStatus(): Promise<RpcNodeStatus> {
    return this.call<RpcNodeStatus>('nexus_getNodeStatus', []);
  }

  /**
   * 获取跨链交易列表
   * @param limit 返回数量上限
   * @param status 可选状态过滤
   */
  async getCrossChainTransactions(
    limit: number = 20,
    status?: string
  ): Promise<RpcCrossChainTx[]> {
    return this.call<RpcCrossChainTx[]>('nexus_getCrossChainTransactions', [
      limit,
      status ?? null,
    ]);
  }
}
