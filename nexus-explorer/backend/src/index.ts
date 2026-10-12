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
 *
 * 静态资源（2026-10-12 新增）：当 `FRONTEND_DIR` 指向已构建的前端产物时，
 * 本服务同时托管该目录（单容器部署形态，见 nexus-explorer/Dockerfile）。
 */

import express, { Request, Response } from "express";
import cors from "cors";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { NexusChainRpcClient } from "./rpc.js";

/** 服务监听端口 */
const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;

/**
 * 前端构建产物目录（可选）。
 *
 * 默认回落到 monorepo 内的 `frontend/dist`（本地 `npm run build:frontend` 之后
 * 即可用 `npm start --workspace @nexus/explorer-backend` 单进程联调）；
 * 容器镜像里由 Dockerfile 显式设为 `/app/frontend/dist`。
 * 目录不存在时**静默跳过**静态托管 —— 后端独立运行时不应因此启动失败
 * （开发态通常只用 Vite dev server 代理 /api）。
 */
const FRONTEND_DIR = process.env.FRONTEND_DIR
  ? resolve(process.env.FRONTEND_DIR)
  : resolve(dirname(fileURLToPath(import.meta.url)), "../../frontend/dist");

/**
 * nexus-core 节点 JSON-RPC 地址
 * 指向 core 的 JsonRpcController（POST /rpc）。core RPC 端口为 19585，
 * 故默认 http://localhost:19585/rpc；生产通过 NEXUS_RPC_URL 覆盖
 * （如 k8s 内 http://nexus-core:19585/rpc）。
 */
const RPC_URL = process.env.NEXUS_RPC_URL ?? "http://localhost:19585/rpc";

/** Express 应用实例 */
const app = express();

/** RPC 客户端 */
const rpcClient = new NexusChainRpcClient(RPC_URL);

/**
 * CORS 白名单（P1，2026-09-17 修复）。
 *
 * 原实现 `app.use(cors())` 允许**任意源**跨站访问本 BFF 的全部接口。
 * 该 BFF 暴露区块/交易/地址查询，虽为公开数据，但开放 CORS 会让任意第三方
 * 站点在用户浏览器中直接读取响应（可被用于流量探测与数据聚合），
 * 也掩盖了真实调用方。改为显式白名单：由 `CORS_ALLOWED_ORIGINS`
 * （逗号分隔）提供，默认仅本地前端开发源。
 */
const CORS_ALLOWED_ORIGINS = (
  process.env.CORS_ALLOWED_ORIGINS ?? 'http://localhost:5173'
)
  .split(',')
  .map((s) => s.trim())
  .filter(Boolean);

app.use(
  cors({
    origin: (origin, callback) => {
      // 无 Origin 头：同源请求或服务端到服务端调用，放行
      if (!origin) return callback(null, true);
      // 未命中白名单：不放行（不抛错，避免 500；浏览器侧会拦截响应）
      return callback(null, CORS_ALLOWED_ORIGINS.includes(origin));
    },
    // 本 BFF 不依赖 Cookie 鉴权（凭据由调用方显式放在请求头），故关闭凭证
    credentials: false,
  }),
);
app.use(express.json());

// ---- 路由：区块 ----

/**
 * GET /api/blocks
 * 查询参数：limit（默认 20）
 * 返回最新区块列表
 */
app.get("/api/blocks", async (req: Request, res: Response) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
    const blocks = await rpcClient.getLatestBlocks(limit);
    res.json(blocks);
  } catch (err) {
    console.error("获取区块列表失败:", err);
    res.status(500).json({ error: "获取区块列表失败" });
  }
});

/**
 * GET /api/blocks/:height
 * 返回指定高度的区块详情
 */
app.get("/api/blocks/:height", async (req: Request, res: Response) => {
  try {
    const height = parseInt(req.params.height, 10);
    if (isNaN(height)) {
      return res.status(400).json({ error: "无效的区块高度" });
    }
    const block = await rpcClient.getBlockByHeight(height);
    if (!block) {
      return res.status(404).json({ error: "区块不存在" });
    }
    res.json(block);
  } catch (err) {
    console.error("获取区块详情失败:", err);
    res.status(500).json({ error: "获取区块详情失败" });
  }
});

// ---- 路由：交易 ----

/**
 * GET /api/tx
 * 查询参数：limit、address（按地址过滤）
 * 返回最新交易列表
 */
app.get("/api/tx", async (req: Request, res: Response) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
    const address = req.query.address as string | undefined;
    const transactions = address
      ? await rpcClient.getTransactionsByAddress(address, limit)
      : await rpcClient.getLatestTransactions(limit);
    res.json(transactions);
  } catch (err) {
    console.error("获取交易列表失败:", err);
    res.status(500).json({ error: "获取交易列表失败" });
  }
});

/**
 * GET /api/tx/:hash
 * 返回指定交易哈希的交易详情
 */
app.get("/api/tx/:hash", async (req: Request, res: Response) => {
  try {
    const tx = await rpcClient.getTransactionByHash(req.params.hash);
    if (!tx) {
      return res.status(404).json({ error: "交易不存在" });
    }
    res.json(tx);
  } catch (err) {
    console.error("获取交易详情失败:", err);
    res.status(500).json({ error: "获取交易详情失败" });
  }
});

// ---- 路由：地址 ----

/**
 * GET /api/address/:addr
 * 返回地址余额、交易数等信息
 */
app.get("/api/address/:addr", async (req: Request, res: Response) => {
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
    console.error("获取地址信息失败:", err);
    res.status(500).json({ error: "获取地址信息失败" });
  }
});

// ---- 路由：合约 ----

/**
 * GET /api/contracts
 * 返回已部署的 WASM 智能合约列表
 */
app.get("/api/contracts", async (_req: Request, res: Response) => {
  try {
    const contracts = await rpcClient.getContractList();
    res.json(contracts);
  } catch (err) {
    console.error("获取合约列表失败:", err);
    res.status(500).json({ error: "获取合约列表失败" });
  }
});

/**
 * GET /api/contracts/:addr
 * 返回指定合约的代码与元信息
 */
app.get("/api/contracts/:addr", async (req: Request, res: Response) => {
  try {
    const contract = await rpcClient.getContract(req.params.addr);
    if (!contract) {
      return res.status(404).json({ error: "合约不存在" });
    }
    res.json(contract);
  } catch (err) {
    console.error("获取合约详情失败:", err);
    res.status(500).json({ error: "获取合约详情失败" });
  }
});

// ---- 路由：节点状态 ----

/**
 * GET /api/node/status
 * 返回当前节点同步状态、最新高度、peers 等信息
 */
app.get("/api/node/status", async (_req: Request, res: Response) => {
  try {
    const status = await rpcClient.getNodeStatus();
    res.json(status);
  } catch (err) {
    console.error("获取节点状态失败:", err);
    res.status(500).json({ error: "获取节点状态失败" });
  }
});

// ---- 路由：跨链交易 ----

/**
 * GET /api/crosschain
 * 查询参数：limit、status
 * 返回跨链交易列表
 */
app.get("/api/crosschain", async (req: Request, res: Response) => {
  try {
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
    const status = req.query.status as string | undefined;
    const txs = await rpcClient.getCrossChainTransactions(limit, status);
    res.json(txs);
  } catch (err) {
    console.error("获取跨链交易失败:", err);
    res.status(500).json({ error: "获取跨链交易失败" });
  }
});

// ---- 健康检查 ----

app.get("/health", (_req: Request, res: Response) => {
  res.json({ status: "ok", timestamp: Date.now() });
});

// ---- 前端静态资源（单容器部署形态） ----

const indexHtml = join(FRONTEND_DIR, "index.html");
const servingFrontend = existsSync(indexHtml);

if (servingFrontend) {
  app.use(express.static(FRONTEND_DIR));

  /**
   * SPA 兜底：前端用 BrowserRouter，`/crosschain`、`/contracts/0xab…` 这类
   * 深链由浏览器直接请求时服务端并没有对应文件，必须回落 index.html，
   * 否则刷新子页面即 404（前端路由「看起来能用」、刷新就坏）。
   *
   * 判定顺序很关键：
   *   1. 只兜底 GET（POST /api/... 未匹配路由应走 404，不能被吞成 HTML）
   *   2. 带扩展名的路径视为静态资源请求（缺失即 404，不回落 —— 否则
   *      资源 404 会变成「返回 HTML 的 200」，脚本解析失败且难以排查）
   *   3. /api/ 与 /health 前缀一律放行给后续处理（防 API 404 被吞）
   */
  app.use((req: Request, res: Response, next) => {
    if (req.method !== "GET") return next();
    const path = req.path;
    if (path.startsWith("/api/") || path === "/health") return next();
    if (/\.[a-zA-Z0-9]+$/.test(path)) return next();
    res.sendFile(indexHtml);
  });
} else {
  console.warn(
    `未找到前端产物（${indexHtml}）—— 仅提供 API。` +
      `执行 npm run build:frontend 或设置 FRONTEND_DIR 以启用页面托管。`,
  );
}

// ---- 启动服务器 ----

app.listen(PORT, () => {
  console.log(`NexusChain Explorer 后端已启动: http://localhost:${PORT}`);
  console.log(`RPC 节点地址: ${RPC_URL}`);
  if (servingFrontend) {
    console.log(`前端产物目录: ${FRONTEND_DIR}`);
  }
});
