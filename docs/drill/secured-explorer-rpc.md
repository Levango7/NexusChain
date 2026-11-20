# Secured Explorer RPC 本地联调钻机（2026-11-21）

## 目的

验证真实的 `Browser-independent BFF → nexus-core localonly RPC` 跨进程鉴权链路，而不是只检查 token 请求头：

1. core 在 `LOCALONLY=true` 下拒绝无 token 的 JSON-RPC；
2. BFF 使用同一组临时配置签发的 JWT 调用 core，并返回真实节点状态；
3. 使用隔离 compose 项目、动态宿主端口和临时密钥，结束后自动删除容器与数据卷。

本钻机不使用生产凭证，也不连接真实资金网关。

## 执行

前置：Docker / Docker Compose 可用；JDK 17 不需要装在宿主机（core 在容器内构建）。

```bash
python -I scripts/drill/secured_explorer_rpc.py
```

成功输出形如：

```text
PASS: direct core RPC without token returns 401
PASS: BFF to secured core RPC returns 200 (latestHeight=0)
PASS: isolated compose project and ephemeral credentials will be removed
```

`latestHeight` 是隔离 PG 中实际链头，不预先写死 42 等演示值。

## 安全与隔离

- `NEXUS_JWT_APP_ID`、`NEXUS_JWT_APP_SECRET` 由进程内 `secrets` 生成；JWT 由同一进程按 core 的 HS256 契约签发；
- 密钥只放入 compose 子进程环境，不写仓库、不写日志、不落临时 env 文件；错误输出会脱敏；
- 所有服务使用 `compose run` + 动态宿主端口，不占用 dev compose 的固定 55432/19585/3001；
- `--use-aliases` 保留 `nexus-pgsql` / `nexus-core` 的服务 DNS 寻址；`--name` 本身不等于 DNS alias；
- `finally` 中执行 `down -v --remove-orphans`，不清理其他项目；
- HTTP 客户端仅允许固定 `127.0.0.1` 和绝对路径，无通用 URL 输入、无重定向，避免把钻机变成 SSRF 工具。

## 网络故障处理

若 `gcr.io` 不可达，可用同 digest 的镜像中转，再按**镜像 ID** 回标原 `nonroot` 标签。不要给 digest 引用直接打 digest 标签（Docker 会拒绝），也不要因此修改生产 Dockerfile 或换成不同 digest。

常见环境错误应逐层区分：Docker daemon 停顿、宿主端口冲突、基础镜像不可达、服务 DNS 缺失、core 未就绪。钻机失败前会读取并脱敏 core 容器日志，不能把这些环境问题写成「鉴权失败」。

## 本轮实证

2026-11-21 在 Windows + Docker Desktop 上通过上述两条判据，隔离资源已清理。真实浏览器另有 `frontend/e2e/home.spec.ts` 的 10 条用例；第三方 gateway/wallet 数据均为 HTTP 层合成，不冒充外部生产联调。
