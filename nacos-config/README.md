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
└── nexus-sentinel-rules.yaml      # Sentinel 限流/熔断规则（设计文档 §4.3.1）
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
2. 发布共享配置 `nexus-common.yaml`（group=NEXUS_GROUP）
3. 发布 Sentinel 规则 `nexus-sentinel-rules.yaml`（group=NEXUS_GROUP）
4. 发布各微服务私有配置占位（`nexus-signing-service.yaml` 等，由 #54/#55 任务完善）

### 3. 验证

- Nacos 控制台 → 配置管理：可见 `nexus-common.yaml` / `nexus-sentinel-rules.yaml`
- Nacos 控制台 → 命名空间：可见 `dev` / `test`

## 端口约定

| 服务 | 端口 | 说明 |
|------|------|------|
| Nacos HTTP | 8848 | 控制台 + REST API |
| Nacos gRPC | 9848 | Nacos 2.x 客户端必需 |
| Sentinel Dashboard | 8858 | 熔断/限流控制台 UI |

## 设计参考

- 设计文档 §4.2.2：bootstrap.yml 配置
- 设计文档 §4.2.3：配置迁移清单
- 设计文档 §4.2.4：Nacos 部署方式
- 设计文档 §4.3.1：Sentinel 规则清单
- 设计文档 §4.3.3：Sentinel 规则持久化