# 全栈部署演练报告（2026-09-12，kind nexus-deploy-drill）

> ③ 方向第二轮：基础设施 + Java 服务栈在 kind 集群的完整部署演练。
> 结论先行：**演练价值已超额兑现**——基础设施层全通（Nacos/Zipkin/PG/mpc×3/core），
> gateway 起通，但 Java 依赖栈在 **Spring Boot 4.0.8** 升级后的兼容性断点
> 集中暴露（6 个真实发现，全部是 compose 环境掩盖、真实 K8s 部署才可见的
> 生产级问题）。

## 终态（kind nexus-deploy-drill 集群）

| 组件 | 状态 | 说明 |
|---|---|---|
| mpc-engine ×3 | ✅ Running 2d+ | mTLS 互联、SealedSecret 注入（fail-closed 修复后兼容） |
| nexus-core | ✅ Running + **真实出块** | 块高 42,745（actuator/prometheus 实测） |
| nexus-gateway | ✅ Running | H2 dev profile；Seata 正确禁用（dev 的 exclude 生效） |
| Nacos v3.1.2 | ✅ Running | standalone + v3 admin API（identity 鉴权）；namespace dev 创建成功 |
| Zipkin 3.4 | ✅ Running | 集群内 endpoint 就绪 |
| Postgres | ✅ Running | 复用演练环境实例 |
| 监控栈全家桶 | ✅ Running | Prometheus/Grafana/Operator/ksm/node-exporter |
| signing/wallet/bridge | ⏸ scaled 0 | 起到第 6 层断点（见发现 #4-6），修复待后续批 |

## 六个真实发现（compose 掩盖、K8s 实测暴露）

### #1 Java 服务镜像缺目录预建（高，部署阻断 ×2）
- **logback 写 ./logs**：`nexuscore.log` 的 RollingFileAppender 要求
  /app/logs 目录——distroless nonroot 无法自建，启动即
  `Logback configuration error` CrashLoop。gateway 与 core 双双命中
  （core 的 Dockerfile 有 dir-builder 阶段但 gateway 没有）。
- **signing 的 LevelDB 建 /app/leveldb**：`Leveldb` Bean init 要求目录
  存在，compose 时代靠 volume 掩盖。
- **修法（已验证）**：K8s 侧 emptyDir 挂载 /app/logs 与 /app/leveldb。
  **镜像侧根治待批**：参照 nexus-core Dockerfile 的 dir-builder 模式
  给 4 个服务 Dockerfile 补目录预建（含 chown nonroot）。

### #2 Nacos 服务发现在 Boot 4 + SCA 2025.1.0.0 下未装配（高，核心功能缺失）
- 症状：gateway Running 但 actuator 显示 `Discovery Client not initialized`，
  Nacos 注册表空（v1 ns API 确认 hosts:[]），全量日志零 Nacos 客户端输出。
- 排除项：starter jar 含实现类 + AutoConfiguration.imports 完整、
  bootstrap.yml 打进 jar、`spring-cloud-starter-bootstrap` 在 classpath、
  nacos-client 3.1.1 在、SPRING_CLOUD_NACOS_DISCOVERY_* env 注入无效。
- 定性：**Boot 4.0.8 的自动装配机制变化与 SCA 2025.1.0.0 的组合断点**。
  修复需要专项验证（SCA 升级到 Boot 4 兼容版本，或迁配置到
  application.yml + 排查 AutoConfiguration 的 condition 评估差异）。
- 影响面：**Feign 服务名解析依赖 discovery——全部跨服务调用在 Boot 4
  部署形态下当前不可用**（fallback 兜底返回 null）。这是全栈演练最重要
  的产出：**没有这次演练，这个问题会在生产部署日爆发**。

### #3 Seata 硬依赖差异（中）
- signing/wallet/bridge 的 `seata.enabled: true` 默认开且**无 dev profile
  排除**（与 gateway 不同——gateway dev 有 exclude），Seata TC 不在时
  `can not connect to 127.0.0.1:8091` 直接 CrashLoop。
- Seata 镜像（seataio/seata-server:2.5.0）被 daocloud/1ms.run 全部 403/
  缺失，国内镜像源不可用——演练用 `SEATA_ENABLED=false` 环境变量绕过。
- **待批**：三服务的 dev/sandbox profile 应与 gateway 对齐补 exclude
  （或 seata.enabled 默认改 ${SEATA_ENABLED:true}）。

### #4 Flyway 在 Boot 4.0.8 下未执行（中）
- signing 配置 `spring.flyway.enabled: true` + 迁移脚本在，但容器日志
  **零 Flyway 输出**（连报错都没有），Hibernate validate 因表缺失失败。
- 定性：Boot 4.0.8 的 Flyway 自动配置断点（Flyway 10.x 与 Boot 4 的
  兼容性问题，与 #2 同族）。
- 演练绕过：`SPRING_JPA_HIBERNATE_DDL_AUTO=create-drop`（H2 内存库
  无损绕过）。**修复待专项**：升级 flyway 依赖或显式 Boot 4 兼容版。

### #5 micrometer Tracer Bean 未装配（中）
- signing 的 `MpcSigner` 构造注入 `io.micrometer.tracing.Tracer` 直接
  `UnsatisfiedDependencyException`。tracing 全套 jar（bridge-brave
  1.6.7 等 8 个）都在 fat jar 里——**依赖在、bean 不装配**，Boot 4.0.8
  与 micrometer-tracing 1.6.7 的自动配置断点（第三例同族问题）。
- Boot 4.0.8 升级注释声称"micrometer-tracing 1.6.7"——**版本组合未经
  独立部署验证**，本次演练证实三者（discovery/flyway/tracer）全是断点。

### #6 Nacos 3.x 运维链路变化（低，运维文档）
- v1 console API 410 Gone、v1 auth/login 500；可用路径：
  v3 admin（`/nacos/v3/admin/core/*` + `nexus-identity` header）读操作；
  v1 cs/configs（写）在 identity header 下返回 true 但疑似假成功（读回
  404）——**config 写入链路存疑，等 #2 修复后用真实客户端验证**。
- 仓库 `nacos-config/init.sh` 全 v1 API，在 Nacos 3.1.2 上已不可用
  （发现 #6a：需按 v3 重写）。

## 已验证可工作的部分

- Nacos standalone in K8s + v3 admin API identity 鉴权（namespace 创建）
- Zipkin 部署 + 就绪
- gateway dev profile（H2 + Seata exclude）完整启动 + actuator health UP
- mpc-engine fail-closed 修复与显式密钥冒烟路径兼容
- core 持续出块（2 天 42,745 块）+ actuator/prometheus 真值稳定

## 后续修复批（按优先级）

1. **Boot 4 兼容性专项**（#2/#4/#5 同族根因）：验证 SCA/Flyway/
   micrometer-tracing 的 Boot 4 兼容版本矩阵；gateway 的
   `discovery not initialized` 为验收标尺。
2. **镜像目录预建**（#1）：4 服务 Dockerfile 补 dir-builder（日志 +
   LevelDB 目录 + chown）。
3. **Seata dev profile 对齐**（#3）：三服务补 exclude 或 env 开关化。
4. **nacos-config/init.sh v3 重写**（#6a）。
5. **k6 压测凭证预置**（backlog 既有项）。

## 演练资产（F:\Nexus\build\，可复用）

- `Dockerfile.java-drill`（4 服务通用 + aliyun init.gradle）
- `fullstack-drill-infra.yaml` / `fullstack-drill-gateway.yaml` /
  `fullstack-drill-services.yaml`（含 SEATA_ENABLED=false + create-drop
  + logs/leveldb emptyDir 的工作配置）
- `nacos-init-v3.sh` / `nacos-publish-real.sh` / 探测脚本组
