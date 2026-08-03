/**
 * NexusChain DevTools — 合约部署器
 *
 * 将编译后的 WASM 合约部署到 NexusChain 链上。
 * 通过 RPC 接口提交部署交易，签名后广播到网络。
 */

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/** 部署输入参数 */
export interface DeployInput {
  /** WASM 文件路径 */
  wasmPath: string;
  /** 初始化参数（可选） */
  initArgs?: Record<string, unknown>;
  /** Gas 限制（可选） */
  gasLimit?: number;
}

/** 部署结果 */
export interface DeployResult {
  /** 是否部署成功 */
  success: boolean;
  /** 合约地址 */
  contractAddress?: string;
  /** 部署交易哈希 */
  txHash?: string;
  /** Gas 消耗 */
  gasUsed?: number;
  /** 错误信息 */
  error?: Error;
}

/** 部署交易 RPC 响应 */
interface DeployTxResponse {
  contractAddress: string;
  txHash: string;
  gasUsed: number;
}

/**
 * ContractDeployer — 合约部署器
 *
 * 读取 WASM 文件，构造部署交易，签名后提交到 NexusChain 节点。
 */
export class ContractDeployer {
  /** NexusChain 节点 RPC 地址 */
  private readonly nodeUrl: string;

  /** 签名密钥文件路径（可选） */
  private readonly keyPath?: string;

  /** 部署请求 ID 计数器 */
  private requestId: number = 0;

  /**
   * @param nodeUrl NexusChain 节点 RPC 地址
   * @param keyPath 签名密钥文件路径（可选，不提供则使用默认密钥）
   */
  constructor(nodeUrl: string, keyPath?: string) {
    this.nodeUrl = nodeUrl;
    this.keyPath = keyPath;
  }

  /**
   * 部署 WASM 合约到 NexusChain 链
   * @param wasmPath WASM 文件路径
   * @param initArgs 初始化参数
   * @param gasLimit Gas 限制
   */
  async deploy(
    wasmPath: string,
    initArgs?: Record<string, unknown>,
    gasLimit?: number
  ): Promise<DeployResult> {
    const absWasmPath = resolve(process.cwd(), wasmPath);

    // 校验 WASM 文件存在
    if (!existsSync(absWasmPath)) {
      return {
        success: false,
        error: new Error(`WASM 文件不存在: ${absWasmPath}`),
      };
    }

    // 读取 WASM 字节码
    const wasmBytes = readFileSync(absWasmPath);
    const wasmBase64 = wasmBytes.toString('base64');

    // 加载签名密钥
    const signingKey = this.loadSigningKey();

    try {
      // 构造 RPC 请求
      const result = await this.sendRpc<DeployTxResponse>(
        'nexus_deployContract',
        [
          {
            wasm: wasmBase64,
            initArgs: initArgs ?? {},
            gasLimit: gasLimit ?? 10000000,
            from: signingKey.address,
          },
          signingKey.privateKey
        ]
      );

      return {
        success: true,
        contractAddress: result.contractAddress,
        txHash: result.txHash,
        gasUsed: result.gasUsed,
      };
    } catch (error) {
      return {
        success: false,
        error: error instanceof Error ? error : new Error(String(error)),
      };
    }
  }

  /**
   * 加载签名密钥
   * @returns 地址与私钥
   */
  private loadSigningKey(): { address: string; privateKey: string } {
    // 骨架阶段：返回占位值
    // 实际实现应从 keyPath 读取密钥文件并解析
    if (this.keyPath) {
      const absKeyPath = resolve(process.cwd(), this.keyPath);
      if (existsSync(absKeyPath)) {
        const keyData = JSON.parse(readFileSync(absKeyPath, 'utf-8'));
        return {
          address: keyData.address ?? '',
          privateKey: keyData.privateKey ?? '',
        };
      }
    }

    // 默认占位密钥（仅开发用）
    console.warn('未提供签名密钥，使用默认开发密钥');
    return {
      address: '0x0000000000000000000000000000000000000000',
      privateKey: '',
    };
  }

  /**
   * 发送 JSON-RPC 请求
   * @param method RPC 方法名
   * @param params 方法参数
   * @returns 响应结果
   */
  private async sendRpc<T>(
    method: string,
    params: unknown[]
  ): Promise<T> {
    const res = await fetch(this.nodeUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: ++this.requestId,
        method,
        params,
      }),
    });

    if (!res.ok) {
      throw new Error(`RPC HTTP 错误: ${res.status}`);
    }

    const data = await res.json();

    if (data.error) {
      throw new Error(`RPC 错误: ${data.error.message}`);
    }

    return data.result;
  }
}
