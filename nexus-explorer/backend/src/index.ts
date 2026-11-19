/**
 * NexusChain Explorer — 后端启动入口。
 *
 * 路由与静态托管全在 `app.ts` 的 `createApp()` 中，本文件只负责监听端口，
 * 以便测试在随机端口上启动同一份应用而不再 import 即监听。
 */
import { createApp } from "./app.js";

const PORT = process.env.PORT ? parseInt(process.env.PORT, 10) : 3000;
const RPC_URL = process.env.NEXUS_RPC_URL ?? "http://localhost:19585/rpc";
const FRONTEND_DIR = process.env.FRONTEND_DIR;

createApp().listen(PORT, () => {
  console.log(`NexusChain Explorer 后端已启动: http://localhost:${PORT}`);
  console.log(`RPC 节点地址: ${RPC_URL}`);
  if (FRONTEND_DIR) console.log(`前端产物目录: ${FRONTEND_DIR}`);
});
