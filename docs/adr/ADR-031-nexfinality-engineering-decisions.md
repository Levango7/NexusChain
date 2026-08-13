# ADR-031: NexFinality 落地中的工程决策与环境约束记录

## 状态

**Accepted**（2026-08-11）

## 背景

本 ADR 记录 NexFinality（ADR-030）实现过程中遇到的**非协议性工程决策**与**环境约束**。
目标读者：未来接手最终性层开发/运维的团队。协议语义已在 ADR-030 冻结，本 ADR
只记录"怎么造"而非"造什么"。

## 决策 1：零 protoc 环境下复用 TRANSACTIONS 通道承载投票消息

### 约束

P2P 消息定义（`nexus-core/nexus-core/src/main/proto/NexusChain.proto`）对应的
`NexusChainOuterClass.java` 是**手工维护的 protobuf 生成代码（16,821 行）**，
本项目未配置 protobuf-gradle-plugin（build.gradle 仅引入 `protobuf-java` 运行时），
且开发环境**没有 protoc 工具链**（`protoc --version` 命令不可用）。

### 决策

不修改 proto 文件、不重新生成消息类。投票消息复用现有通道：

```
Code.TRANSACTIONS (proto 已定义，Payload 已支持解析)
  └─ TransactionType.VOTE (proto 已定义，链上投票语义的预留枚举)
       └─ Transaction.payload bytes ← FinalityVoteCodec.encode(vote)
            payload[0] = 0x5A 魔数前缀
```

`SyncManager.onMessage` 新增 `case TRANSACTIONS` → `onTransactions`：
筛出 `transaction_type == VOTE` 且 `payload` 带魔数（0x5A）的交易，
解码后注入 `FinalityVoteBroadcaster.onVoteReceived()`。

### 备选方案与否决原因

| 方案 | 否决原因 |
|---|---|
| 修改 proto 新增 `message FinalityVote` + `Code.FINALITY_VOTE(15)` | 生成代码为手工维护，无 protoc 无法可靠重生成；手工改 1.6 万行消息类涉及 builder/parseFrom/serializar 整套体系，风险不可控 |
| 复用 `Code.PROPOSAL` 的 `Proposal.payload` 字段 | **尝试过且失败**：`Proposal` 消息只含 `block` 字段，无 payload 字段（编译错误定位），属挂错载体 |
| JSON 直接走通用 body | 无对外契约的私有通道不如复用 proto 已定义的 VOTE 语义 |

### 代价与解锁条件

- 语义代价：投票消息混入交易通道，靠魔数区分。若后续引入独立 protobuf 枚举可无缝替换（`FinalityVoteP2PCodec.isVotePayload` 是唯一判定点）
- 解锁：安装 protoc 3.22.2（与项目 protobufVersion 一致）→ 改 proto → gradle 生成。届时仅需新增 `Code.FINALITY_VOTE`，替换 SyncManager 路由

## 决策 2：BLS12-381 物理绑定挂起，采用接口先行 + 收集式降级

### 约束

`SignatureAggregator`（M3 架构层）需要 BLS12-381 Java 实现，但探明所有获取路径不可达：

| 候选 | 结果 |
|---|---|
| `supranational:blst:0.3.11`（官方 Java 绑定） | Maven Central 无此坐标（仅在 GitHub Packages），阿里云镜像 404 |
| `org.hyperledger.besu:bls12-381` / `tech.pegasys.teku.internal:crypto` | Maven Central 不存在 |
| `org.apache.milagro:amcl` 系列 | 阿里云 404，坐标不存在 |
| BouncyCastle 1.78 内置 bls12381 曲线 | `bcprov-jdk18on-1.78.jar` 无 `bls12381` 包（grep 计数=0） |
| GitHub Releases 手动下载 jar | 连接被重置 / 404 |

### 决策

- 抽象层先行：`SignatureAggregator` 接口 + `CollectingAggregator` 收集式默认实现
- `FinalityGadget` 最终化判定前调用聚合验签：**验签失败 → fail-closed 否决不最终化**（权重累积保留）
- 物理绑定（blst jar）挂起，三个解锁条件记录于 `docs/adr/M3-BLS-blocking-notes.md`
- 注入点 `FinalityGadget.setSignatureAggregator()` 已预留，blst 落地后仅替换实现类，调用方零改动

### 关键教训

BouncyCastle 的 BLS12-381 支持**不在主 `bcprov` artifact**（更高版本/拓展 artifact）。
后续若升级 BC，需先验证 `unzip -l bcprov*.jar | grep bls12381` 再决策。

## 决策 3：`@Component` 装配 via 构造器注入（不用字段注入 + 无参构造）

### 决策

`FinalityGadget` 标注 `@Component`，依赖 `ValidatorRegistry`/`StakingService`
（均为 `@Component`/`@Service` bean）通过构造器注入。

### 教训

初稿曾尝试"无参构造 + final 字段置 null + 后续 setter"模式——这会导致
Spring 实例化后字段为 null，任何方法调用 NPE。立即回退，坚持构造器注入。

## 决策 4：checkpointHash 口径统一为原始哈希字节

### 背景

初版 `FinalityCoordinator` 用 `block.getHashHexString().getBytes()` 作为
checkpoint 标识（hex 字符串再转字节 = 双重编码），导致
`FinalityGadget.isFinalized(epoch, hash)` 查询口径不一致（单元测试 L138 失败定位）。

### 决策

`FinalityCoordinator.onBlock` 直接使用 `Block.getHash()` 的**原始字节**作为 checkpoint，
十六进制编码仅用于展示，不进入内部 key。

## 决策 5：FinalityService"BFT 权重优先、确认数降级"

### 决策

网关查询最终性按次顺序：

1. `txHash → block_height → epoch → GET /rpc/v1/finality/epoch/{epoch}`（BFT 权重）
2. 最终性层未装配（`NOT_ACTIVE`）/ RPC 异常 / 空 Map → 降级原 confirmations 驱动

### 教训（两个真实 bug）

1. **Mockito 空 Map 陷阱**：未 stub 的 Map 返回类型 mock 返回**空 Map 而非 null**，
   导致"finality != null"误判成立 → 修复为校验 `finality_status` 字段有效性
2. **整数截断**：`voted*100/total` 重算进度（60000/900=66 而非 67）→ 修复为
   RPC 百分数直接承载（threshold=100）

## 决策 6：oracle 模块依赖方向（防循环）

### 决策

治理→验证者集轴（`ValidatorSetPort` / `ValidatorSetExecutor`）放在 `nexus-oracle`（进程内库），
通过**端口接口**注入实现，杜绝 `nexus-oracle → nexus-core` 反向依赖（两者构成 composite build，
反向依赖会成环）。具体实例化由宿主（gateway）在装配层完成。

## 决策 7：全链路验证采用"编排集成测试"，真实组件 + 存储层 mock

### 背景

分点单测（Coordinator 投票 / Gadget 阈值 / P2P 编解码）各自通过，但缺
"真实出块 → 检查点 → 自动投票 → 最终化"的整链证据。`Leveldb` 为文件存储
（`factory.open(file)`），真机跑需完整基础设施（种子 DataBase/Miner 配置等），
交付前无法在无 Postgres/LevelDB 环境验证全链路。

### 决策

新增 `FinalityChainOrchestrationTest`（a3457a7）：

```
真实：PosConsensusEngine（Ed25519 签名）→ 唯一验证人注册 →
      FinalityCoordinator（epoch 边界自动投票）→ FinalityGadget（2/3 权重判定）
mock：StateDB / NexusChainBlockChain / PackageMiner（仅存储层）
```

验证目标：单验证人每 epoch 检查点 100% 权重立即 FINALIZED；
跨 epoch 隔离（未投下 epoch 前不误判）；高度单调递增。

### 关键教训

1. **验证人公钥必须与引擎签名密钥一致**：`propose()` 按引擎公钥
   `findValidatorByPublicKeyHex` 查找本节点验证人，注册地址与签名密钥
   不一致会导致 proposer 命中失败（返回 null）——曾用旧模式先踩此坑。
2. **父块引用用递增引用而非队列 `peek()`**：队列 `peek()` 恒返回队头
   导致出块高度停滞（expected 3 but was 2），改为 `lastParent` 引用 +
   高度→区块历史 Map。

## 决策 8：真机联调——core 在 Spring Boot 3 + Postgres 完整启动的障碍链

### 背景

NexFinality 的最终性 RPC 已建但从未在真机验证：core 的 `Start.java` 是 Spring Boot 3
应用，但**长期无法在 SB3 容器启动**（此前环境仅有 mock 单测）。2026-08-13 以
Docker Postgres 16 + `application-local.properties`（`pos` 模式）推进会话逐个排除障碍。

### 排除的障碍链（5 个真实启动缺陷）

| # | 缺陷 | 症状 | 修复 |
|---|---|---|---|
| 1 | slf4j 1.7.36 硬编码 | `LoggerFactory is not a Logback LoggerContext` 启动即崩 | `slf4jVersion 1.7→2.0.13`（49d59c7） |
| 2 | `PosConsensus` 双实现歧义 | `PosMiningScheduler` 注入 "2 beans found" | `DefaultPosConsensus` 加 `@Primary` |
| 3 | `@PathVariable` 无显式名 | finality RPC 500 `Name for argument of type [long] not specified`（编译未开 `-parameters`） | 显式 `@PathVariable("epoch")` |
| 4 | `FinalityGadget` 多构造器 | `No default constructor found` | `@Autowired` 标注双参构造器 |
| 5 | H2 兼容 Postgres 方言 | `ALTER TABLE ... OWNER TO` 等 H2 不支持 | **放弃 H2 兼容，用 Docker Postgres**（真机正解） |

### 真机验证结果（2026-08-13）

```
core   : Started Start in ~21s（Docker PG + pos + local profile），/rpc/v1/height 200
gateway: Started GatewayApplication（sandbox + --nexus.chain.rpc-url=http://localhost:19585）
链路   : 注册商户 → 建单 → pay → confirm(PAID) → GET /api/v2/orders/{id}/finality 200
         echo "{\"finality_status\":\"UNKNOWN\",...note:\"chain unreachable or tx not found\"}"
验证   : gateway→FinalityService→ChainRpcClient→core /rpc/v1/transaction/{hash}/status
         真实 HTTP 往返；core 返回 NOT_FOUND → 网关正确降级 UNKNOWN。
         GET /rpc/v1/finality/epoch/1 直连 core：BFT 权重路径 2000 正常。
```

### 关键教训

1. **BouncyCastle 无 bls 后 H2 兼容是打地鼠**：`RDBMSBlockChainImpl` 硬编码
   Postgres `OWNER TO` 语法，H2 兼容需改生产代码——**无 Postgres 时放弃 H2、
   用 Docker PG 是正解**（H2 曾试 `DATABASE_TO_LOWER`/`CASE_INSENSITIVE_IDENTIFIERS`
   两方案均撞墙后止损）。
2. **Windows Git Bash 后台进程会被回收**：跨 tool 调用的 `&` 子进程在 bash
   退出后消失，真机联调必须在**单 bash 调用内**完成 启动→等待→curl→kill。
3. **core 从「无法在 SB3 启动」到「完整启动 + RPC 工作」** 是五个独立缺陷的
   串联排除，此前任何单测都无法暴露（单测 mock 存储/上下文）。

## 与环境约束并列的既有问题记录

- `L2L1EndToEndTest`（Hardhat EDR 兼容性）：**基线既有失败**，与本次改动无关。
  经 git stash 基线复跑证实（同一 5 用例失败）。README/CHANGELOG 已记载，
  L1 真实节点验证待 Hardhat 环境修复。
- mpc-engine Rust 构建：C 编译器缺位历史问题（README 已记载）。

## 影响模块

| 模块 | 决策 |
|---|---|
| nexus-core | 1（P2P 复用 TRANSACTIONS）/ 2（BLS 抽象）/ 3（@Component）/ 4（hash 口径）/ 7（编排集成测试）/ 8（真机启动障碍链） |
| nexus-gateway | 5（BFT 优先降级）/ 8（真机联调验证） |
| nexus-oracle | 6（端口防循环） |

## 结论

ADR-030 的协议语义与 ADR-031 的工程决策共同构成 NexFinality 的可验证实现基线。
除 M0/M3（BLS 物理绑定）与 L2 环境问题外，其余里程碑已闭环并通过测试：
最终性层 45+ 用例（含出块→检查点→最终化整链编排、持久化恢复、并发安全、
治理权重刷新）、网关 13 用例；core 已在 Docker Postgres 真机启动并完成
gateway→core 最终性 RPC 真实往返验证（决策 8），全量 1081 通过
（唯一失败 L2L1EndToEndTest 为基线 Hardhat 环境问题）。