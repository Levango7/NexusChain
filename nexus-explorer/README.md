# NexusChain Explorer

NexusChain 区块链浏览器 — 查询 NexusChain（NEX）链上数据的区块浏览器。

## 功能

| 功能 | 页面 | 状态 |
|------|------|------|
| 区块查询 | `/`、`/block/:height` | 已接入 core RPC（最新区块列表 + 区块详情） |
| 交易查询 | `/`、`/tx/:hash` | 已接入 core RPC（最新交易列表 + 交易详情） |
| 地址查询 | `/address/:addr` | 已接入（余额、交易数） |
| 合约查看 | `/contracts`、`/contracts/:addr` | 已接入（合约列表 + 代码/ABI 详情；`abi` 节点不返回时显式说明） |
| 跨链交易追踪 | `/crosschain` | 已接入（含状态筛选；数据源为 core 跨链交易 RPC） |
| 节点状态 | 首页页头 | 已接入（chainId / 最新高度 / 最新哈希） |
| 支付编排控制台 | `/orchestration` | 需商户 API 凭证（走 gateway 认证接口，配置见 `/settings`） |

**历史状态提醒**：合约页与跨链页直到 2026-10-12 才存在 —— 此前后端端点
（`GET /api/contracts`、`GET /api/crosschain`）一直可用、README 也一直宣称支持，
但前端**没有任何页面消费它们**，即「后端有能力、用户不可达」。同类缺陷与该仓
2026-09-16 审查发现的 `/orchestration` 零入口是同一类（端点存在 ≠ 可用）。

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | React 18 + TypeScript + Tailwind CSS（Vite 构建） |
| 后端 | Node.js + Express（BFF，透传 nexus-core RPC） |
| 数据源 | nexus-core 节点 RPC（`POST /rpc`，端口 19585） |
| 构建 | npm workspaces (monorepo) |

## 项目结构

```
nexus-explorer/
├── package.json          # monorepo 根配置（workspaces: frontend / backend）
├── Dockerfile            # 单容器镜像（构建上下文 = repo 根，见下「部署」）
├── frontend/             # React 前端
│   └── src/
│       ├── App.tsx       # 路由表（9 条路由）
│       ├── pages/        # 页面（含 ContractsPage / ContractPage / CrossChainPage）
│       ├── api/client.ts # BFF 客户端（每端点声明必需字段，缺失即报契约错误）
│       └── types/        # 与后端逐字对齐的数据模型（字段来源写在注释里）
└── backend/              # Node.js 后端（BFF）
    └── src/
        ├── index.ts      # Express 服务器（REST + 前端静态托管 + SPA 兜底）
        └── rpc.ts        # nexus-core JSON-RPC 客户端
```

## 快速开始（开发）

```bash
# 安装依赖（在 monorepo 根目录，即本目录）
npm install

# 终端 1：启动后端（默认 :3000，代理到 core 的 19585）
npm run dev --workspace @nexus/explorer-backend

# 终端 2：启动前端 dev server（:5173，/api 已代理到 :3000）
npm run dev --workspace @nexus/explorer-frontend
```

质量门禁（前端）：`npm run check --workspace @nexus/explorer-frontend`
= format:check + lint + vitest + build + 构建产物校验 + 设计令牌对比度校验。

## 部署

### 单容器（推荐）

前端产物与 BFF 由**同一个进程**提供：`/` 出页面，`/api/**` 出数据。
同源带来两个实际好处 —— 前端 API 基址编译期留空（相对路径），**无需任何跨源配置**；
BrowserRouter 的深链（`/crosschain`、`/contracts/NEX…`）由 BFF 的 SPA 兜底回落
`index.html`，刷新子页面不会 404。

```bash
# 构建（构建上下文必须是 repo 根目录）
docker build -f nexus-explorer/Dockerfile -t nexus-explorer:local .

# 运行（NEXUS_RPC_URL 指向真实的 core 节点；默认 localhost:19585 在容器内指向自身）
docker run --rm -p 3001:3000 \
  -e NEXUS_RPC_URL=http://nexus-core:19585/rpc \
  nexus-explorer:local
```

也可用仓库根目录的 compose：`docker compose up -d nexus-explorer`
（dev：宿主 `127.0.0.1:3001` → 容器 `3000`；prod 见 `docker-compose.prod.yml`）。

镜像由发布链构建并推送 GHCR（`release.yml` / `ci.yml` 的 `nexus-explorer` 条目），
与其他服务同标签口径（`vX.Y.Z` / `latest` / `:sha`）。

**未做（诚实声明）**：K8s/Helm 部署形态尚未提供 —— `deploy/helm/charts/` 里没有
explorer 子 chart，`deploy/k8s/` 也没有对应静态清单（两者必须同步落地，
否则 `k8s-sync-check` 会判定双路径漂移）。当前容器化路径 = compose。

### 配置项

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `PORT` | `3000` | BFF 监听端口 |
| `NEXUS_RPC_URL` | `http://localhost:19585/rpc` | nexus-core JSON-RPC 地址；容器内需改为服务名 |
| `FRONTEND_DIR` | `<repo>/frontend/dist` | 前端产物目录；不存在则**只提供 API**（不阻断启动） |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | 逗号分隔白名单；同源部署下前端走相对路径，无需放行自身 |
| `VITE_API_BASE` | 开发态 `http://localhost:3000` | **构建期**变量；镜像内构建时置空串 → 请求走相对路径（同源） |

## 许可证

MIT
