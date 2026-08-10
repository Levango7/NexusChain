/**
 * NexusChain Explorer — 后端 Express 服务器骨架
 *
 * 提供 REST API 路由，代理查询 nexus-core 节点的 RPC 数据。
 * 路由列表：
 *   GET  /api/blocks          — 获取最新区块列表
 *   GET  /api/blocks/:height  — 获取指定区块详情
 *   GET  /api/tx              — 获取最新交易列表
 *   GET  /api/tx/:hash        — 获取指定交易详情
 *   GET  /api/address/:addr   — 获取地址信息（余额、交易数）
 *   GET  /api/contracts       — 获取已部署合约列表
 *   GET  /api/contracts/:addr — 获取合约详情
 *   GET  /api/node/status     — 获取节点状态
 *   GET  /api/crosschain      — 获取跨链交易列表
 */

import express, { Request, Response } from 'express';
import cors from 'cors';
import { NexusChainRpcClient } from './rpc.js';

/** 服务监听端口 */
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;

/**
 * nexus-core 节点 JSON-RPC 地址
 * 指向 core 的 JsonRpcController（POST /rpc）。core RPC 端口为 19585，
 * 故默认 http://localhost:19585/rpc；生产通过 NEXUS_RPC_URL 覆盖
 * （如 k8s 内 http://nexus-core:19585/rpc）。
 */
const RPC_URL = process.env.NEXUS_RPC_URL ?? 'http://localhost:19585/rpc';

/** Express 应用实例 */
const app = express();

/** RPC 客户端 */
const rpcClient = new NexusChainRpcClient(RPC_URL);

// 中间件
app.use(cors());
app.use(express.json());

// ---- 路由：区块 ----

/**
 * GET /api/blocks
 * 查询参数：limit（默认 20）
 * 返回最新区块列表
 */
app.get('/api/blocks', async (req: Request, res: Response) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
    const blocks = await rpcClient.getLatestBlocks(limit);
    res.json(blocks);
  } catch (err) {
    console.error('获取区块列表失败:', err);
    res.status(500).json({ error: '获取区块列表失败' });
  }
});

/**
 * GET /api/blocks/:height
 * 返回指定高度的区块详情
 */
app.get('/api/blocks/:height', async (req: Request, res: Response) => {
  try {
    const height = parseInt(req.params.height, 10);
    if (isNaN(height)) {
      return res.status(400).json({ error: '无效的区块高度' });
    }
    const block = await rpcClient.getBlockByHeight(height);
    if (!block) {
      return res.status(404).json({ error: '区块不存在' });
    }
    res.json(block);
  } catch (err) {
    console.error('获取区块详情失败:', err);
    res.status(500).json({ error: '获取区块详情失败' });
  }
});

// ---- 路由：交易 ----

/**
 * GET /api/tx
 * 查询参数：limit、address（按地址过滤）
 * 返回最新交易列表
 */
app.get('/api/tx', async (req: Request, res: Response) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
    const address = req.query.address as string | undefined;
    const transactions = address
      ? await rpcClient.getTransactionsByAddress(address, limit)
      : await rpcClient.getLatestTransactions(limit);
    res.json(transactions);
  } catch (err) {
    console.error('获取交易列表失败:', err);
    res.status(500).json({ error: '获取交易列表失败' });
  }
});

/**
 * GET /api/tx/:hash
 * 返回指定交易哈希的交易详情
 */
app.get('/api/tx/:hash', async (req: Request, res: Response) => {
  try {
    const tx = await rpcClient.getTransactionByHash(req.params.hash);
    if (!tx) {
      return res.status(404).json({ error: '交易不存在' });
    }
    res.json(tx);
  } catch (err) {
    console.error('获取交易详情失败:', err);
    res.status(500).json({ error: '获取交易详情失败' });
  }
});

// ---- 路由：地址 ----

/**
 * GET /api/address/:addr
 * 返回地址余额、交易数等信息
 */
app.get('/api/address/:addr', async (req: Request, res: Response) => {
  try {
    const address = req.params.addr;
    const balance = await rpcClient.getBalance(address);
    const txCount = await rpcClient.getTransactionCount(address);
    res.json({
      address,
      balance,
      txCount,
    });
  } catch (err) {
    console.error('获取地址信息失败:', err);
    res.status(500).json({ error: '获取地址信息失败' });
  }
});

// ---- 路由：合约 ----

/**
 * GET /api/contracts
 * 返回已部署的 WASM 智能合约列表
 */
app.get('/api/contracts', async (_req: Request, res: Response) => {
  try {
    const contracts = await rpcClient.getContractList();
    res.json(contracts);
  } catch (err) {
    console.error('获取合约列表失败:', err);
    res.status(500).json({ error: '获取合约列表失败' });
  }
});

/**
 * GET /api/contracts/:addr
 * 返回指定合约的代码与元信息
 */
app.get('/api/contracts/:addr', async (req: Request, res: Response) => {
  try {
    const contract = await rpcClient.getContract(req.params.addr);
    if (!contract) {
      return res.status(404).json({ error: '合约不存在' });
    }
    res.json(contract);
  } catch (err) {
    console.error('获取合约详情失败:', err);
    res.status(500).json({ error: '获取合约详情失败' });
  }
});

// ---- 路由：节点状态 ----

/**
 * GET /api/node/status
 * 返回当前节点同步状态、最新高度、peers 等信息
 */
app.get('/api/node/status', async (_req: Request, res: Response) => {
  try {
    const status = await rpcClient.getNodeStatus();
    res.json(status);
  } catch (err) {
    console.error('获取节点状态失败:', err);
    res.status(500).json({ error: '获取节点状态失败' });
  }
});

// ---- 路由：跨链交易 ----

/**
 * GET /api/crosschain
 * 查询参数：limit、status
 * 返回跨链交易列表
 */
app.get('/api/crosschain', async (req: Request, res: Response) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
    const status = req.query.status as string | undefined;
    const txs = await rpcClient.getCrossChainTransactions(limit, status);
    res.json(txs);
  } catch (err) {
    console.error('获取跨链交易失败:', err);
    res.status(500).json({ error: '获取跨链交易失败' });
  }
});

// ---- 健康检查 ----

app.get('/health', (_req: Request, res: Response) => {
  res.json({ status: 'ok', timestamp: Date.now() });
});

// ---- 启动服务器 ----

app.listen(PORT, () => {
  console.log(`NexusChain Explorer 后端已启动: http://localhost:${PORT}`);
  console.log(`RPC 节点地址: ${RPC_URL}`);
});
