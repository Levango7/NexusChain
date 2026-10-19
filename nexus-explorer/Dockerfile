# syntax=docker/dockerfile:1
# ============================================================================
# NexusChain Explorer 镜像 —— 单容器形态：Express BFF 同时提供 /api/** 与前端产物
#
# 构建（与其他服务一致，上下文 = repo 根目录）：
#   docker build -f nexus-explorer/Dockerfile -t nexus-explorer:local .
#
# 为什么单容器：explorer 的两半（React 前端 + Express BFF）没有独立扩缩容需求，
# 而**分开部署会引入一个必须显式配置的跨源基址**（前端默认打 localhost:3000），
# 部署到任何非本机环境都会静默指向用户自己的机器。同源托管后，前端 API 基址
# 留空（相对路径 /api/**），一个端口既是页面也是接口，无需任何环境变量对齐。
#
# 注意：本 Dockerfile 依赖根级 .dockerignore —— 其中已把 nexus-explorer/**/node_modules
# 与两端 dist 排除，避免宿主机陈旧产物进入上下文（见该文件注释）。
# ============================================================================

# ---- 构建阶段：先装依赖（层缓存），再构建两端产物 ----
FROM node:24-alpine AS build
WORKDIR /app

# 仅先拷清单：源码改动不会让 npm ci 的层缓存失效
COPY nexus-explorer/package.json nexus-explorer/package-lock.json ./
COPY nexus-explorer/frontend/package.json ./frontend/
COPY nexus-explorer/backend/package.json ./backend/

# 全量依赖（含 vite/tsc 等 devDependencies —— 构建需要）
RUN npm ci

COPY nexus-explorer/ ./

# 同源部署：把前端 API 基址置为空串，编译后请求走相对路径 /api/**
# （client.ts 的 `import.meta.env.VITE_API_BASE ?? "http://localhost:3000"`；
#  空串不是 null/undefined，故 `??` 不会回落到 localhost —— 这正是我们要的）
ARG VITE_API_BASE=""
ENV VITE_API_BASE=${VITE_API_BASE}

RUN npm run build:frontend && npm run build:backend

# ---- 运行阶段依赖：只装 backend workspace 的生产依赖（express/cors） ----
#
# 为什么带 `--workspace @nexus/explorer-backend`：运行时只执行 backend/dist/index.js，
# 前端产物是**已打包的静态文件**（依赖早已被 vite 内联进 bundle），故 frontend 的
# 依赖树在运行镜像里纯属死重。限定 workspace 后 node_modules 由 ~84MB 降到 ~28MB。
# 仍从根 package-lock.json 安装，版本可复现（不用裸 npm install —— 那会重新解析版本）。
FROM node:24-alpine AS prod-deps
WORKDIR /app
COPY nexus-explorer/package.json nexus-explorer/package-lock.json ./
COPY nexus-explorer/frontend/package.json ./frontend/
COPY nexus-explorer/backend/package.json ./backend/
RUN npm ci --omit=dev --workspace @nexus/explorer-backend

# ---- 运行阶段 ----
FROM node:24-alpine AS runtime
ENV NODE_ENV=production \
    PORT=3000 \
    FRONTEND_DIR=/app/frontend/dist
WORKDIR /app

# 2026-10-19（修复 master Security Scan 阻断）：删除基线镜像自带的 npm/npx/corepack。
#
# 为什么：node:24-alpine 的 npm（11.19.1）自带一份打包的 node_modules，其中
# brace-expansion / http-cache-semantics / undici 携带 4 个 HIGH CVE
# （CVE-2026-102276 / 102278 / 93748 / 19534），路径均在
# /usr/local/lib/node_modules/npm/node_modules/** —— 属**基线镜像层**而非应用依赖
# （应用 lockfile 不含这些版本，相关条目亦均为 dev-only）。2026-10-18 Trivy 镜像
# 矩阵补齐 explorer 后首扫即命中，master Security Scan 连续红灯。
#
# 处置：运行镜像只执行 `node backend/dist/index.js`，包管理器永不被调用——
# 按"可消除项不豁免"原则整树删除（含 corepack 及其 yarn/pnpm shims），
# 同时缩小运行时文件系统与供应链暴露面；不写入 .trivyignore。
RUN rm -rf /usr/local/lib/node_modules/npm \
           /usr/local/lib/node_modules/corepack \
           /usr/local/bin/npm /usr/local/bin/npx \
           /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg

# backend/package.json 必须一并拷入：其中 "type": "module" 决定 dist/*.js 按 ESM 解析
COPY --from=prod-deps /app/node_modules ./node_modules
COPY --from=build /app/backend/package.json ./backend/package.json
COPY --from=build /app/backend/dist ./backend/dist
COPY --from=build /app/frontend/dist ./frontend/dist

# 非 root 运行；本服务只读文件，无写入需求
USER node
EXPOSE 3000

# 健康检查用 busybox wget（alpine 自带），与 compose / k8s 探针同口径
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:3000/health || exit 1

CMD ["node", "backend/dist/index.js"]
