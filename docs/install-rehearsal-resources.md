# 安装预演资源需求（B1 真实安装验证）

> 建立于 2026-10-12。本文回答一个问题：**"把这套平台按交付形态装一遍"需要多少资源**。
>
> 数据来源分两类，全文逐项标注：
> **【实测】**= 本机 2026-10-12 用 `docker stats` / 进程工作集（Working Set）量到的真实值；
> **【估算】**= 由实测的同类服务外推（Java 服务按 JVM 参数 + 实测同类进程外推）。
> 客户现场若用 OceanBase / 达梦等替代 MySQL，另需按厂商最小规格叠加（本表未含）。

## 1. 三档预演与资源需求

| 档位 | 内容 | 内存 | vCPU | 时长 |
|---|---|---|---|---|
| **L1 最小闭环**（compose 全栈） | MySQL 8 + PostgreSQL（core）+ Redis + Nacos + Seata + Zipkin + gateway/wallet/signing/bridge/api-gateway/core + explorer | **~5–6GB**（启动峰值 ~7GB）【估算，鱼骨见 §2】 | 4 | 40–60 min |
| **L2 + MPC** | L1 + mpc-engine×3 + DKG 仪式 + 2-of-3 签名 + 冷恢复 | L1 **+0.3–0.5GB**（引擎进程本身小，峰值在仪式期 CPU） | +2 | +20 min |
| **L3 含 K8s 形态** | L2 换到 K8s：kind（单节点起）+ `deploy/helm` 全 chart（含 MPC chart + SealedSecret 生产密钥路径） | **7–9GB**【实测锚点：kind 节点 control-plane 884MB / worker 663–1355MB；本机 4 节点 kind 集群合计 **3.9GB**】 | 6–8 | +60 min |

**构建期额外开销**【实测，发生在预演机上时才有】：

- 单次 Gradle 编译峰值需 **≥2.5–3GB 空闲**：`-Xmx1500m` 实测可跑；2GB daemon 在宿主空闲
  <1GB 时两次失败（一次 `Gradle build daemon disappeared`（OOM 被杀）、一次构建缓存 transform
  元数据损坏）。
- Docker 多阶段构建单镜像峰值 **0.3–1.5GB**（Java 镜像最大）。
- → 若要"在预演机上现构建"，再留 **3–4GB**；用 CI 已推 GHCR 的镜像（`v2.72.0` 起 13 个镜像齐备）可省掉这段。

**建议规格（结论）**：

- 只做 L1/L2（验证"能起、能跑通业务"）：**8GB 可用内存 + 4 vCPU + 20GB 空磁盘**
- 要做 L3（客户装环境的真实预演）：**16GB 可用内存 + 8 vCPU + 30GB 空磁盘**
- 磁盘不是瓶颈【实测】：本机 C 108.4GB / D 105.4GB / E 213.3GB / F 48.2GB 空闲。

## 2. L1 鱼骨（各服务的量级依据）

| 组件 | 内存 | 依据 |
|---|---|---|
| MySQL 8 | 400–600MB | 同为关系库的实测：`dify-db_postgres` 55MB（空闲）/ `elasticsearch-mcp` 1.8GB（ES 重）；MySQL 取中位【估算】 |
| PostgreSQL（core 链库） | 50–150MB | 【实测】`nexussky-v28-pg` 等 PG 容器量级 |
| Redis / Zipkin / Seata | 10MB / 300MB / 400–600MB | Redis【实测】；Seata/Nacos 为 JVM 服务【估算】 |
| Nacos（集群模式 + 外接 MySQL） | 600–900MB | JVM 服务，含配置/注册缓存【估算】 |
| gateway / wallet / signing / bridge / api-gateway | 各 500–900MB（`-Xmx512m` 起） | **【实测】本会话以 prod profile 启 wallet-service（`-Xmx420m`）实测可跑；同类 JVM 服务实测 2.4GB 的是天枢 GCS（`-Xmx` 未限）** |
| nexus-core（链节点） | 200–400MB | LevelDB + 共识线程【估算】 |
| explorer（Node BFF） | 60–100MB | 【实测】本会话镜像运行实测 ~80MB |

> 启动期注意：JVM 服务首个请求/首次迁移时内存会往上跳 30% 左右；compose 的 `start_period`
> 已按此设置（gateway 40s / core 60s）。

## 3. 本机现状与差距【实测 2026-10-12】

- 物理 **31.2GB**，可用仅 **0.5–1.5GB**。占用方：
  - Docker/WSL VM **7.4GB**（其中：本机其他项目的 6 个栈 —— deploy-drill kind 3.9GB、dataengine kind 2.0GB、ES+Kibana 2.4GB、dify 15 容器 2.4GB、opsmesh 18 容器、corps、nexussky）
  - 本会话 ZCode 4.6GB（4 个进程）+ 天枢 GCS（aerofleet）java 2.4GB
  - 并行 agent 会话：cline / opencode / kilo 均在吃 CPU（10 秒采样 4.4 / 3.0 / 0.6 秒）
- **差距：L3 需 ~16GB 可用，当前 ~1GB → 差 15GB。**

### 三条可行路径

1. **重启本机（推荐，成本 0）**：重启后 WSL/VM 归零，且已落地的
   `autoMemoryReclaim=gradual`（`~/.wslconfig`，2026-10-12 加）开始生效——VM 会把空闲内存
   主动还给 Windows，不再长期占着高水位。预计可得 20GB+ 可用；预演窗口独占 2–3 小时即可。
2. **授权 `wsl --shutdown`**：立即回收 ~7GB；代价 = 停掉所有容器（含其他会话的 kind 集群，
   它们没有 restart 策略、需要手工 `docker start`）。适合"其他会话此刻不需要集群"的时机。
3. **换机器/云主机**：按 §1 建议规格（16GB / 8 vCPU）新起一台，与日常开发机解耦
   ——这也是客户现场的真实形态（客户环境不会与我们共享 Docker 宿主）。

## 4. 每轮预演的固定清单（可复现）

1. 建库：`docker start nex-mysql-test`（本会话建的预演实例）；
   或对新实例执行 `deploy/config/init-mysql-dbs.sh`（五库 + 最小权限账号）。
2. compose 全栈：`docker compose -f docker-compose.prod.yml --env-file .env.prod up -d`
   （需 17 个必填变量，清单见 `.env.prod.example`）。
3. 判据（缺一不可）：
   - 各服务 `/actuator/health`（Java）与 `/health`（explorer）= **UP**
   - gateway Flyway 迁到 **V97**、wallet 迁到 **V4**（`flyway_schema_history` 核对）
   - `GET /api/v1/compliance/sanction-list/imports` 可返回台账（v2.72.0 新增）
   - explorer `/`、`/crosschain`、`/contracts` 三个深链 200
4. MPC（L2+）：`bash scripts/gen-mpc-engine-configs.sh --layout compose-prod`
   → `python scripts/mpc-wallet-ceremony.py --sign-probe`（仪式 + 验签探针）
5. K8s（L3）：`kind create cluster` → `helm install`（`deploy/helm/charts/*`）
   → SealedSecret 生产密钥路径（参考 `deploy/k8s/SECRET-MANAGEMENT.md`）
6. 收尾：把每一步的**判据输出**贴进预演报告（安装预演的交付物是"证据"，不是"跑过了"）。
