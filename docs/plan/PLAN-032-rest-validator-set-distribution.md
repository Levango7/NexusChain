# 方案 PLAN-032：rest 形态验证人集分发（F1-over-rest 第一切片）

- **状态**：已交付（2026-10-25）；单测 + 实机端到端（零人工预置）均通过
- **前置**：PLAN-029 §六（rest 形态三个运行时缺陷已修 + F2 路径实证）；
  PLAN-030（F1：grpc 中继与全表回放）；PLAN-001 签名扩展切片 4
  （`validators_synced.signed_payload`：回放载荷逐字节复用）
- **范围**：`GET /consensus/validator-set`（服务端）+ `SyncClient.syncValidatorSet()`
  （rest 客户端周期拉取）+ 拦截器白名单；复用既有验签/重放/幂等注册路径
- **不包含**：rest 形态的其它 P2P 语义（实时广播/中继）、grpc 追块速率改进、
  P2P 端点鉴权改造

---

## 一、问题（承接已登记边界）

PLAN-027（F1）修复的是 **grpc** 路径的验证人集中继与全表回放；PLAN-029 §六 明确：
**rest 形态没有 grpc P2P 广播通道**，验证人集分发属未做 ⇒ rest 节点拉到的区块
全部卡在 `unknown proposer (validator set not synchronized)`，追块"到手落不进库"
（验证时靠测试床预置 `validators_synced` 绕过，只是权宜）。

## 二、方案（三条改动，全部复用既有语义）

### 2.1 服务端：`GET /consensus/validator-set`

- `ConsensusController.getValidatorSet()`：返回**全表回放载荷的 base64 数组**。
- 数据来源：`SyncManager.validatorSetReplayPayloadsForRest()` —— 与 grpc 全表
  回放**同一构建方法**（抽取为 `buildValidatorSetReplayPayloads()`）：
  存量签名载荷**逐字节复用**、老行**退化重建**（无签名），上限
  `MAX_VALIDATORS_PER_REPLAY`（64）。
- 未装配 SyncManager 时返回空数组（不 500）。

### 2.2 客户端：`SyncClient.syncValidatorSet()`（30s 周期）

- rest 形态专属（`SyncClient` 本身 `@ConditionalOnProperty(p2p.mode=rest)`）。
- 对 `consensusConfig.getPeers()` 每个 peer：`GET /consensus/validator-set`
  → base64 解码 → **`SyncManager.onValidatorSetPayload()`**。
- 该入口即 `handleValidatorSet`：**验签（切片 2+4）、重放护栏（nonce 水位）、
  幂等注册、落库（含载荷原文）全部与 grpc 接收一致**——REST 只是传输层。

### 2.3 拦截器白名单（踩坑固化）

- `WebSecurityConfig` 的 `LocalRpcInterceptor` 挂 `/**`，P2P 对端的
  `/consensus/*` 端点必须逐个列入 `excludePathPatterns`。
- **本切片首测踩坑**：漏加 `/consensus/validator-set` ⇒ 拦截器 `return false` ⇒
  **HTTP 200 空响应**（无 4xx/5xx、无异常日志），rest 端拉到空数组**静默失效**。
  已加白名单，并在 `WebSecurityConfig` 注释中固化"新增 P2P 端点必须同步加白名单"。
- **追根（2026-10-25 加固）**：白名单只是触发面，真正的放大因素是 `LocalRpcInterceptor`
  的拒绝路径 `return false` 且**不写响应**——任何被拦截的端点都表现为 200 空 body。
  已修：所有拒绝（缺 token / 验签不通过 / 解析异常）现在写 **401 + JSON body**；
  顺带修 `token != ""` 引用比较漏洞与 JWT 解析异常逃逸（500）。见 CHANGELOG 同条。

## 三、测试

| 用例 | 断言 |
|------|------|
| `restPayloadEntryProcessesSignedAddThroughSamePath` | REST 入口（`onValidatorSetPayload`）处理签名 add → 注册 + 中继（与 grpc 同路径） |
| `restPayloadListReusesStoredSignedPayloadVerbatim` | REST 列表与 grpc 回放同源：存量载荷逐字节复用且带签名；老行退化重建 |
| （既有）`SyncManagerValidatorSetRelayTest` 13 例 | grpc 侧行为不回归 |

## 四、实机端到端（2026-10-25，零人工预置）

- 环境：A（新构建，Windows JVM，`enforce=true`）作服务端；临时 rest 节点
  **全量重置**（`nexuschain_rest` drop/create + 缓存清空，`validators_synced` 为空）。
- 证据（rest-node.log）：
  - `validator-set sync from 127.0.0.1:19585: 3 payload(s) applied` ×12；
  - `Validator-set add: address=nv3g.../mRCg.../aKURT... registered=true (via P2P)`
    —— 三条均经 REST 分发 + 同一处理路径注册；
  - `receive blocks startListening` ×34；`unknown proposer` **0**；
  - 高度 **0 → 332+** 持续单调（6 分钟窗口）。
- 对照（本切片前）：同环境空库 rest 节点全部区块卡 `unknown proposer`，
  必须人工预置共享表才能追块。

## 五、明确边界（不夸大）

- **只读分发**：本切片只做"周期性全表分发"，不做 rest 形态的实时广播/中继
  （rest 无广播通道；新消息到达的时效仍低于 grpc）。
- **端点无鉴权**：与既有 `/consensus/status`、`/consensus/blocks` 同口径
  （P2P 对等端本就可达）；载荷逐字节带签名，`enforce` 下接收端必验签——
  分发通道本身不引入新的信任假设。公钥/质押额本就随 grpc 广播传播。
- **不改变 grpc**：`SyncClient` 仅 rest 装配；grpc 部署行为不变。
- **未做**：rest 集群的 F1 中继等价物；grpc 大缺口追块速率（独立项，见 roadmap #35 边界）。
- C 容器镜像（Docker）当前仍是白名单修复前的构建，需在合并后重建镜像；
  A 侧已用新构建实测。
