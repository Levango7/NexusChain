# TODO/FIXME 全量分诊（2026-09-05）

全仓 `*.java` / `*.rs` 共 **27 处** TODO 标记，逐一核实后分为四组。
本批处置：A 组过时注释已清理（功能早已落地）；B/C/D 组保留为路线图
追踪项，原文未动。

## A 组：过时注释——功能已落地，本批已清理（6 处标记，4 个文件）

核实证据：实现真实存在且有专项测试护航。

| 位置 | 原注释 | 核实结论 |
|---|---|---|
| `nexus-compliance/.../DefaultAmlService.java:48` | STR 持久化 TODO(v2.0.0) | `persistReport`（:86-95）JSONL CREATE+APPEND 真实落盘 |
| `nexus-compliance/.../DefaultAmlService.java:172` | 同上 | 同上 |
| `nexus-compliance/.../DefaultAmlServiceStrPersistenceTest.java:13` | 测试类 TODO(v2.0.0) | 测试即落地证明 |
| `nexus-gateway/.../OrchestrationWebhookDispatcher.java:44-45` | Redis dedup/DLQ TODO(v2.0.0) | `tryDedup` SETNX+TTL / DLQ List 已实现，可选注入+本地回退 |
| `nexus-gateway/.../OrchestrationWebhookDispatcher.java:100` | 同上 | 同上 |
| `nexus-gateway/.../OrchestrationWebhookDispatcherRedisTest.java:17` | 测试类 TODO(v2.0.0) | 测试即落地证明 |

## B 组：v2.0.0 roadmap 追踪项——保留（3 处）

均已标注 tracked in v2.0.0 roadmap，维持原追踪渠道：

| 位置 | 内容 | 备注 |
|---|---|---|
| `nexus-compliance/.../DefaultDidService.java:27` | DID 文档从进程内注册表替换为链上解析 | 依赖 core DID 模块成熟度 |
| `nexus-settlement/.../DefaultFundSweepService.java:164` | 冷钱包转移多签审批流 | 依赖审批流组件 |
| `nexus-gateway/.../PaymentServiceImpl.java:251` | 订单确认的交易详情校验 | 有警告日志兜底，防护增强项 |

## C 组：mpc-engine 多密钥/KMS——保留（15 处）

`config.rs`（9 处）+ `persistence.rs`（6 处）。S4 修复（2026-08-31）的
**有意设计**：`storage_key_version` 已随文件头记录、当前单密钥实现、
`kms` 模式 fail-closed（未实现即拒绝启动，不静默降级）。

- 多密钥支持（按版本号从 `storage_keys` 映射选密钥）——生产密钥轮换的前提
- KMS API 解密接入——依赖部署环境（K8s + 云 KMS）

安全非紧急（fail-closed + 密钥不入库），与 K 批（K8s 部署）合并规划。

## D 组：GG20 路径 ZK proof——保留，与 GG20 处置决策合并（3 处）

`MpcSignatureAggregator.java:35/93/170`：份额级 ZK 范围证明未接入
（当前仅 hex/长度格式校验）。该类属 GG20 旧路径——CGGMP21 迁移后
需先做**旧路径处置决策**（退役 or 保留维护）：
- 退役 → 本组 TODO 随路径一并消失
- 保留 → 补份额 ZK 验证（multi-party-ecdsa 的 proof 结构）

## 统计

| 组 | 处数 | 处置 |
|---|---|---|
| A 过时注释 | 6 | 本批清理 |
| B roadmap 项 | 3 | 保留 |
| C 多密钥/KMS | 15 | 保留，随 K 批规划 |
| D GG20 ZK | 3 | 保留，随旧路径决策 |
| 合计 | 27 | |
