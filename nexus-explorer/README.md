# NexusChain Explorer

NexusChain 区块链浏览器 — 用于查询 NexusChain（NEX）链上数据的全功能区块浏览器。

## 功能

- **区块查询** — 浏览最新区块、查看区块详情（高度、哈希、交易数、出块时间）
- **交易查询** — 搜索并查看交易详情（发送方、接收方、金额、状态、合约调用）
- **地址查询** — 查看地址余额、交易历史、持仓信息
- **节点状态** — 监控全网节点状态、出块节点、同步进度
- **合约查看** — 浏览已部署的 WASM 智能合约、查看合约代码与 ABI
- **跨链交易追踪** — 追踪 NexusChain 跨链桥交易状态与跨链资产流向

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | React + TypeScript + Tailwind CSS |
| 后端 | Node.js + Express |
| 数据源 | nexus-core 节点 RPC 接口 |
| 构建 | npm workspaces (monorepo) |

## 项目结构

```
nexus-explorer/
├── package.json          # monorepo 根配置
├── frontend/             # React 前端
│   ├── package.json
│   └── src/
│       └── App.tsx
└── backend/              # Node.js 后端
    ├── package.json
    └── src/
        ├── index.ts      # Express 服务器
        └── rpc.ts        # RPC 客户端
```

## 快速开始

```bash
# 安装依赖（在 monorepo 根目录）
npm install

# 启动后端
npm run dev --workspace @nexus/explorer-backend

# 启动前端
npm run dev --workspace @nexus/explorer-frontend
```

## 配置

后端默认连接 `http://localhost:19585/rpc` 的 nexus-core 节点（core 的 JsonRpcController，RPC 端口 19585）。可通过环境变量 `NEXUS_RPC_URL` 自定义节点地址。

## 许可证

MIT
