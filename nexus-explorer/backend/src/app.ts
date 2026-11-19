/**
 * NexusChain Explorer — 可测试的 Express 应用工厂。
 *
 * 与 `index.ts` 的唯一区别是**不再在模块 import 时监听端口**：
 * 生产由 index.ts 调 `createApp().listen()`；测试可注入 RPC 地址与前端产物目录，
 * 在随机端口上验证真实 HTTP 链路（而不是 mock 掉 BFF 本身）。
 */
import express, { Request, Response } from "express";
import cors from "cors";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { NexusChainRpcClient } from "./rpc.js";
import { HeadBroadcaster, type HeadEvent } from "./headStream.js";

export interface CreateAppOptions {
  /** nexus-core JSON-RPC 地址；未传时读 NEXUS_RPC_URL，默认 localhost:19585/rpc。 */
  rpcUrl?: string;
  /** 前端构建产物目录（可选；存在 index.html 时启用静态托管 + SPA 兜底）。 */
  frontendDir?: string;
}

export function createApp(options: CreateAppOptions = {}) {
  const FRONTEND_DIR = options.frontendDir
    ? resolve(options.frontendDir)
    : process.env.FRONTEND_DIR
      ? resolve(process.env.FRONTEND_DIR)
      : resolve(dirname(fileURLToPath(import.meta.url)), "../../frontend/dist");
  const RPC_URL = options.rpcUrl ?? process.env.NEXUS_RPC_URL ?? "http://localhost:19585/rpc";
  const rpcClient = new NexusChainRpcClient(RPC_URL);
  const app = express();

  /** CORS 白名单（默认仅本地前端开发源；无 Origin 的服务端调用放行）。 */
  const CORS_ALLOWED_ORIGINS = (process.env.CORS_ALLOWED_ORIGINS ?? "http://localhost:5173")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);

  app.use(
    cors({
      origin: (origin, callback) => {
        if (!origin) return callback(null, true);
        return callback(null, CORS_ALLOWED_ORIGINS.includes(origin));
      },
      credentials: false,
    }),
  );
  app.use(express.json());

  // ---- 区块 ----
  app.get("/api/blocks", async (req: Request, res: Response) => {
    try {
      const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
      res.json(await rpcClient.getLatestBlocks(limit));
    } catch (err) {
      console.error("获取区块列表失败:", err);
      res.status(500).json({ error: "获取区块列表失败" });
    }
  });

  app.get("/api/blocks/:height", async (req: Request, res: Response) => {
    try {
      const height = parseInt(req.params.height, 10);
      if (isNaN(height)) return res.status(400).json({ error: "无效的区块高度" });
      const block = await rpcClient.getBlockByHeight(height);
      if (!block) return res.status(404).json({ error: "区块不存在" });
      res.json(block);
    } catch (err) {
      console.error("获取区块详情失败:", err);
      res.status(500).json({ error: "获取区块详情失败" });
    }
  });

  // ---- 交易 ----
  app.get("/api/tx", async (req: Request, res: Response) => {
    try {
      const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
      const address = req.query.address as string | undefined;
      res.json(
        address
          ? await rpcClient.getTransactionsByAddress(address, limit)
          : await rpcClient.getLatestTransactions(limit),
      );
    } catch (err) {
      console.error("获取交易列表失败:", err);
      res.status(500).json({ error: "获取交易列表失败" });
    }
  });

  app.get("/api/tx/:hash", async (req: Request, res: Response) => {
    try {
      const tx = await rpcClient.getTransactionByHash(req.params.hash);
      if (!tx) return res.status(404).json({ error: "交易不存在" });
      res.json(tx);
    } catch (err) {
      console.error("获取交易详情失败:", err);
      res.status(500).json({ error: "获取交易详情失败" });
    }
  });

  // ---- 地址 ----
  app.get("/api/address/:addr", async (req: Request, res: Response) => {
    try {
      const address = req.params.addr;
      const balance = await rpcClient.getBalance(address);
      const txCount = await rpcClient.getTransactionCount(address);
      res.json({ address, balance, txCount });
    } catch (err) {
      console.error("获取地址信息失败:", err);
      res.status(500).json({ error: "获取地址信息失败" });
    }
  });

  // ---- 合约 ----
  app.get("/api/contracts", async (_req: Request, res: Response) => {
    try {
      res.json(await rpcClient.getContractList());
    } catch (err) {
      console.error("获取合约列表失败:", err);
      res.status(500).json({ error: "获取合约列表失败" });
    }
  });

  app.get("/api/contracts/:addr", async (req: Request, res: Response) => {
    try {
      const contract = await rpcClient.getContract(req.params.addr);
      if (!contract) return res.status(404).json({ error: "合约不存在" });
      res.json(contract);
    } catch (err) {
      console.error("获取合约详情失败:", err);
      res.status(500).json({ error: "获取合约详情失败" });
    }
  });

  // ---- 节点状态 ----
  app.get("/api/node/status", async (_req: Request, res: Response) => {
    try {
      res.json(await rpcClient.getNodeStatus());
    } catch (err) {
      console.error("获取节点状态失败:", err);
      res.status(500).json({ error: "获取节点状态失败" });
    }
  });

  // ---- 链头 SSE（单实例轮询、变化才广播；浏览器可 EventSource 重连） ----
  const headBroadcaster = new HeadBroadcaster(rpcClient);
  app.get("/api/events/head", (req: Request, res: Response) => {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    });
    res.flushHeaders?.();
    const send = (event: string, data: unknown) => {
      res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    };
    send("init", headBroadcaster.currentHead());
    const unsubscribe = headBroadcaster.subscribe((e: HeadEvent) => send("head", e));
    const keepalive = setInterval(() => res.write(": ping\n\n"), 15_000);
    if (typeof keepalive === "object" && "unref" in keepalive) keepalive.unref();
    req.on("close", () => {
      clearInterval(keepalive);
      unsubscribe();
    });
  });

  // ---- 跨链交易 ----
  app.get("/api/crosschain", async (req: Request, res: Response) => {
    try {
      const limit = Math.min(parseInt(req.query.limit as string, 10) || 20, 100);
      const status = req.query.status as string | undefined;
      res.json(await rpcClient.getCrossChainTransactions(limit, status));
    } catch (err) {
      console.error("获取跨链交易失败:", err);
      res.status(500).json({ error: "获取跨链交易失败" });
    }
  });

  app.get("/health", (_req: Request, res: Response) => {
    res.json({ status: "ok", timestamp: Date.now() });
  });

  // ---- 前端静态资源（存在 index.html 时启用；否则仅提供 API） ----
  const indexHtml = join(FRONTEND_DIR, "index.html");
  const servingFrontend = existsSync(indexHtml);
  if (servingFrontend) {
    app.use(express.static(FRONTEND_DIR));
    // SPA 兜底：GET 深链回落 index.html；API/health 与静态扩展名不吞。
    app.use((req: Request, res: Response, next) => {
      if (req.method !== "GET") return next();
      const path = req.path;
      if (path.startsWith("/api/") || path === "/health") return next();
      if (/\.[a-zA-Z0-9]+$/.test(path)) return next();
      res.sendFile(indexHtml);
    });
  } else {
    console.warn(`未找到前端产物（${indexHtml}）—— 仅提供 API。`);
  }

  return app;
}
