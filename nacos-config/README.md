# Nacos 配置初始化

本目录包含 NexusChain 开发环境 Nacos 的初始化配置与脚本。

## 目录结构

```
nacos-config/
├── README.md                      # 本文件
├── init.sh                        # Nacos 启动后执行的初始化脚本（创建 namespace + 发布配置）
├── init/
│   └── custom.properties          # Nacos Server 扩展配置（挂载至 /home/nacos/init.d/）
├── nexus-common.yaml              # 共享配置（所有微服务共享：链节点 RPC、日志、Actuator）
├── nexus-sentinel-rules.yaml      # Sentinel 限流/熔断规则（设计文档 §4.3.1）
├── nexus-seata.yaml               # Seata Client 共享配置（设计文档 §4.2.2，group=NEXUS_GROUP）
├── seata-server.properties        # Seata Server Nacos 配置（设计文档 §4.2.1，group=SEATA_GROUP）
└── seata-server-db.sql            # Seata Server DB 建表 SQL（store.mode=db 模式必需）
```

## 使用流程

### 1. 启动 Nacos + Sentinel

```bash
# 在项目根目录
docker-compose up -d nacos sentinel-dashboard
```

- Nacos 控制台：http://localhost:8848/nacos （开发环境已关闭鉴权）
- Sentinel Dashboard：http://localhost:8858

### 2. 执行初始化脚本

```bash
# 等待 Nacos 完全启动后（约 10-20s）
bash nacos-config/init.sh
```

脚本完成：
1. 创建 namespace：`dev` / `test`（`prod` 使用默认 `public` namespace）
2. 发布共享配置 `nexus-common.yaml` / `nexus-sentinel-rules.yaml` / `nexus-seata.yaml`（group=NEXUS_GROUP）
3. 发布 Seata Server 配置 `seataServer.properties`（group=SEATA_GROUP，设计文档 §4.2.1）
4. 发布各微服务私有配置占位（`nexus-signing-service.yaml` 等，由 #54/#55 任务完善）

### 3. 验证

- Nacos 控制台 → 配置管理：可见 `nexus-common.yaml` / `nexus-sentinel-rules.yaml` / `nexus-seata.yaml`（group=NEXUS_GROUP）
- Nacos 控制台 → 配置管理：可见 `seataServer.properties`（group=SEATA_GROUP）
- Nacos 控制台 → 命名空间：可见 `dev` / `test`

## Seata 配置说明

### Seata Server 配置（seata-server.properties）

- **dataId**：`seataServer.properties`
- **group**：`SEATA_GROUP`（Seata 专用 group，与 NEXUS_GROUP 区分）
- **type**：`properties`
- **内容**：事务组映射（`service.vgroup-mapping.nexus-tx-group=default`）、存储模式（`store.mode=db`）、DB 连接、Server 超时/重试、metrics
- **DB 建表**：使用 `store.mode=db` 模式前需先执行 `seata-server-db.sql`（创建 global_table / branch_table / lock_table / distributed_lock）
- **开发环境**：可切换为 `store.mode=file`（内嵌存储，无需 MySQL），见文末注释

### Seata Client 共享配置（nexus-seata.yaml）

- **dataId**：`nexus-seata.yaml`
- **group**：`NEXUS_GROUP`（与 nexus-common.yaml 一致）
- **type**：`yaml`
- **引入方式**：各微服务通过 `bootstrap.yml` 的 `spring.cloud.nacos.config.shared-configs` 引入
- **内容**：`tx-service-group: nexus-tx-group`、`service.vgroup-mapping`、registry/config（Nacos）、`data-source-proxy-mode: AT`、client TM/RM 参数
- **适用服务**：gateway（AT 模式）、signing-service（TCC 模式，私有配置覆盖 data-source-proxy-mode）
- **不适用**：wallet-service（Phase 3 不接入 Seata，设计文档 D8 决策）

### 版本兼容 POC

Seata 2.0.0 与 SpringBoot 3.2.5 + SCA 2023.0.1.0 兼容性已验证（任务 #60 T1），POC 报告见 `.codeartsdoer/specs/microservice-phase3/seata-poc/POC-REPORT.md`。

## 端口约定

| 服务 | 端口 | 说明 |
|------|------|------|
| Nacos HTTP | 8848 | 控制台 + REST API |
| Nacos gRPC | 9848 | Nacos 2.x 客户端必需 |
| Sentinel Dashboard | 8858 | 熔断/限流控制台 UI |
| Seata Server TC | 8091 | Seata Server 事务协调器端口 |
| Seata Server Web | 7091 | Seata Server 控制台 |
| Seata metrics | 9898 | Prometheus 指标（可选） |

## 设计参考

- 设计文档 §4.2.2：bootstrap.yml 配置
- 设计文档 §4.2.3：配置迁移清单
- 设计文档 §4.2.4：Nacos 部署方式
- 设计文档 §4.3.1：Sentinel 规则清单
- 设计文档 §4.3.3：Sentinel 规则持久化
- 设计文档 §3.1.3 / §4.2.1：Seata Server 部署 + Nacos 配置
- 设计文档 §4.2.2：Seata Client 接入 + application.yml 配置
- 设计文档 §4.1：版本对齐矩阵（Seata 2.0.0 + SCA 2023.0.1.0）