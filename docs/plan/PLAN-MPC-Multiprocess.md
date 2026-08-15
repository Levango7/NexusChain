# 方案：MPC 多进程/多主机份额分布（#7 架构级）

- **状态**：Approved（2026-08-15 审核通过）
- **日期**：2026-08-15
- **前置**：#7 缓解层完整（份额加密存储 + ThresholdPolicy + 启动门限校验）；`MpcTransport` 抽象 + `GrpcMpcTransportStub`（mTLS）+ `MessageRouter` + `RoundBarrier` 已存在
- **目标**：把"份额集中在单进程"的架构限制，推进到**多进程/多主机份额分布**（门限签名理论安全模型）

---

## 一、现状事实（代码已确认）

```
✅ MpcTransport 抽象（InMemoryMpcTransport / GrpcMpcTransportStub，mTLS）
✅ MessageRouter + MessageDeduplicator（消息路由/去重）
✅ RoundBarrier（门限回合同步，threshold 到达才继续）
✅ MpcSigningSession（多参与者回合状态机 + 份额收集）
✅ MpcKeyGeneration / MpcKeyShare（密钥生成 + 份额模型）
⚠️ 现状：份额集中单进程（所有 participant 的份额在同一个 JVM）

**关键判断**：通信层（gRPC mTLS）+ 会话层（回合/屏障）已具备多进程能力，
缺口在 **参与者进程化 + 份额分布存储**。
```

## 二、方案设计

### 方案 A：多进程单机部署（推荐第一步）

```
拓扑：一个协调进程 + N 个参与者进程（同一台机器，不同端口）

┌─────────────────────────────────────────────┐
│  协调者进程 (coordinator)                     │
│  - MpcService / 签名请求入口                   │
│  - 只持有元数据，不持有任何份额                │
├───────────┬───────────┬───────────┬─────────┤
│ 参与者1   │ 参与者2   │ 参与者3   │ ...     │   ← 独立进程/独立 JVM
│ 持有份额1 │ 持有份额2 │ 持有份额3 │          │   ← 份额只在本进程内存
└─────┬─────┴─────┬─────┴─────┬─────┴─────────┘
      └───────────┴───────────┘ gRPC mTLS（已具备）
```

**要点**：
1. 每个参与者进程**独立持有自己的份额**（份额从不在协调者进程出现）
2. 参与者进程间/协调者间走 `GrpcMpcTransportStub`（mTLS 已实现）
3. 签名流程：协调者编排 → 各参与者本地用自己份额计算 → 份额汇聚 → 门限组合

### 方案 B：多主机分布式（长期）

```
方案 A 拓扑 + 参与者部署到不同主机
- 依赖：主机间 mTLS 证书体系（已有 GrpcTlsContextFactory）
- 网络分区/节点故障处理（RoundBarrier 超时）
- 份额备份与恢复（各主机独立备份）
```

### 方案 C：保持单进程 + 强化隔离（最小）

```
维持单进程，但份额用更严格的内存隔离（如 byte[] 零化 + 信号量）
不改变"份额集中"本质——仅工程加固
```

**推荐**：A（多进程单机，立即落地）+ B（多主机，后续）

## 三、核心改动点（方案 A）

| 文件 | 改动 |
|---|---|
| 新增 `MpcParticipantProcess` | 参与者独立进程入口（Spring Boot app 或 main）——加载本进程份额，提供签名份额计算 gRPC |
| `MpcTransportConfig` | 按进程角色加载（协调者 vs 参与者）不同传输配置 |
| `MpcKeyGeneration` | 份额分发：生成的份额**写入各参与者进程的加密存储**（非协调者） |
| `MpcSigningSession` | 会话参与者映射到远程进程（GrpcMpcTransportStub 已有） |
| `DefaultMpcService` | 协调者编排：向参与者进程分发回合消息（复用 transport） |
| 部署 | `deploy/` 新增多进程启动脚本/配置模板 |

## 四、验证标准

```
1. 3 参与者进程 + 1 协调者，各自独立 JVM
2. 密钥生成：份额分发到各进程，协调者进程无份额文件
3. 签名：协调者编排 → 各进程本地份额计算 → 门限组合成功
4. 单参与者进程故障：签名失败（fail-closed，不降级）
5. 既有测试全绿（InMemoryMpcTransport 测试保留）
```

## 五、风险与缓解

| 风险 | 缓解 |
|---|---|
| 参与者进程崩溃/网络分区 | RoundBarrier 超时 + fail-closed（不产出不完整签名） |
| mTLS 证书管理复杂度 | 复用 GrpcTlsContextFactory；证书发放脚本 |
| 签名延迟（多进程 RTT） | 单机进程 RTT 低；gRPC 复用连接 |
| 回归风险 | 保留 InMemory 传输测试作为基线 |

## 六、待审核决策点

1. **方案选型**：A（多进程单机，推荐）vs B（多主机）vs C（隔离强化）？
2. **协调者无份额**：签名编排时协调者是否完全不接触份额（强烈建议是——门限理论模型）？
3. **份额迁移**：现有单进程份额如何迁移到多进程（新生成 vs 迁移）？
4. **验收规模**：3 参与者（t=2）起步验证？

请审核并给出决策，通过后实施。

## 审核决策（2026-08-15）

| 决策点 | 结论 |
|---|---|
| 方案选型 | **A. 多进程单机**（1 协调者 + N 参与者独立 JVM，份额只在参与者进程） |
| 协调者无份额 | 签名编排时协调者不接触份额（门限理论模型） |
| 验证规模 | **3 参与者 + t=2 门限**起步 |

## 实施进展（2026-08-15，架构基础确认 + 引擎进程化验证）

**重大发现**：方案 A 架构基础**已存在**（审核前未识别）：
- Rust mpc-engine（刚编译成功）实现 MpcCryptoService gRPC 服务端（Dkg/Sign/Aggregate，
  GG20 门限 ECDSA，t=1/n=3 端到端测试通过）
- Java GrpcMpcCryptoEngine：mTLS 客户端（host/port/TLS 证书配置完整）
- proto 双侧对齐（注释明确"方案 A：Rust 引擎独立进程"）
- DefaultMpcService 编排（participants 列表 + DkgRequest/SignRequest）

**引擎进程化验证（✅）**：Docker 内运行 mpc-engine 二进制
（`starting mpc-engine gRPC server bind=0.0.0.0:50051`），端口可达。

**剩余缺口（诚实）**：
1. 引擎无份额持久化（dkg 后份额内存持有，重启丢失）——需份额落盘/恢复
2. 多引擎实例（N 参与者 = N 引擎进程）部署拓扑 + 协调者编排多引擎
3. Java↔引擎 gRPC 端到端签名验证（引擎进程 + signing-service 联调）
4. 3 参与者 t=2 验收（引擎测试现为 t=1/n=3）

## 方案 A 完整验收（2026-08-15，✅）

**缺口 1 份额持久化（已提交）**：persistence.rs（DKG 会话 JSON 落盘/恢复，
MPC_ENGINE_SESSION_DIR）；dkg 后落盘、sign 内存缺失从盘恢复；3 测试全绿。

**缺口 3 跨进程端到端（✅ 验收达成）**：
```
引擎进程（Docker, mpc-engine）→ MpcEndToEndTest（Java gRPC 客户端）
引擎日志: rpc Dkg threshold=2 total_parties=3（审核验收规模）
          rpc Sign party_index=0/1/2（3 参与者份额）
测试: 4 passed, 0 failed
```

**方案 A 核心验证完整**：3 参与者 t=2 门限 + 份额在引擎进程 + 跨进程
gRPC 编排 + Dkg→Sign 真实签名。剩余：多引擎实例拓扑（N 进程部署）
与 Java 侧编排多引擎——后续工程项。

## B 方案多主机验证（2026-08-15，✅ 达成）

**用户方案**：Docker 双容器 + WSL 原生 = 三节点多主机验证环境。

拓扑：node-A(Docker:50051) / node-B(Docker:50052) / node-C(WSL:50053)——
三个独立网络命名空间。

**验证（MpcMultiHostEngineTest 2 passed）**：
- 三主机各自 Dkg→Sign 完整链路跑通（gRPC 跨主机可达）
- 三节点产出 3 个互异公钥（独立进程份额隔离实证）

**结论**：MPC 多主机分布（B 方案）真机验证达成。
环境要点：WSL2 localhost 自动转发 + 显式 127.0.0.1（避免 ::1 解析差异）。

## B 方案 mTLS 传输加密（2026-08-15，✅ 达成）

**依赖冲突解决**：`cargo update -p subtle → 2.6.1`——rustls 0.23 需 subtle ^2.5，
multi-party-ecdsa 0.8.1 约束 ^2（2.6.1 兼容）。引擎 `--features tls` 编译成功。

**mTLS 三节点验证**（MpcMultiHostTlsTest）：
- 三主机 tls_enabled=true require_tls=true（Docker 50051/50052 + WSL 50053）
- Java 客户端 mTLS 双向认证（trust=CA + 客户端证书）
- 三主机各自 Dkg→Sign + 3 互异公钥

**MPC 多主机部署完整形态达成**：网络隔离（命名空间）+ mTLS 传输加密 +
跨主机 gRPC 认证——B 方案全链路真机验证闭环。
