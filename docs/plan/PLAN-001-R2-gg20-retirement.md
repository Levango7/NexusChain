# PLAN-001-R2：GG20 旧路径退役（合并单批——CGGMP21 独占）

状态：**设计稿 R2，待审核**（按惯例通过后实施）
日期：2026-09-08
作废前版：PLAN-001 v1（"分两步"方案）——5 轮迭代失败 + force-push 撤回后证伪，
v1 的致命假设错误："单点删 proto RPC 会让 Java 业务侧编译断"（grep 实证见 §2.3）。

## 1. 结论性修订（v1 教训 → R2 决策）

| v1 假设 | 实证 | R2 决策 |
|---|---|---|
| 分两步（先 Rust 后 Java） | 5 轮迭代失败；Rust 侧死代码 + proto RPC 删除 + Java 编译 cascade 互相纠缠 | **单批原子完成**：Rust + proto + Java 必须同一 commit 落地 |
| DTO 保留可规避 Java 断 | DTO 是 type 但 RPC method 删了——`MpcCryptoServiceGrpc` 生成 stub 不再有 `dkg()` 等方法，`ColdWalletMultiSigService`/`DefaultMpcService` 的 `stub.dkg` 编译失败 | **DTO 与 RPC 同步删**；Java SPI 用 `Cg*` 原生接口重建 |
| 影响面 ~15 文件 | grep 实证 **主代码 23 + 测试 14 = 37 文件** | 工作量按 37 文件估，非 v1 的"1.5-2 天" |

## 2. 影响面（grep 实证，2026-09-08）

### 2.1 Rust 引擎侧（删 6 文件 + 4 依赖 + 5 旧 RPC）

| 文件 | 处置 |
|---|---|
| `mpc-engine/src/gg20.rs` | 整文件删（GG20 密码学核心） |
| `mpc-engine/src/dkg.rs` | 删（GG20 DKG handler） |
| `mpc-engine/src/sign.rs` | 删（GG20 sign handler） |
| `mpc-engine/src/aggregate.rs` | 删（GG20 aggregate handler） |
| `mpc-engine/src/distributed.rs` | 删（GG20 distributed DKG） |
| `mpc-engine/tests/integration_test.rs` | 删（6 个 GG20 cluster E2E） |

Cargo.toml：multi-party-ecdsa 0.8.1 / curv-kzen 0.9 / kzen-paillier 0.4.2 /
zk-paillier 0.4.3 / round-based 0.1 / secp256k1 0.20 / nix 0.27 移除（cggmp21 唯一栈）。

proto/mpc_crypto.proto：`Dkg`/`Sign`/`Aggregate`/`RelayDkgMessage`/`RelaySignMessage`/`DistStatus`
6 个 RPC + 相关 10 个 DTO（DkgRequest/Response/SignRequest/Response/AggregateRequest/Response/
DistDkgMessage/DistSignMessage/DistStatusRequest/Response/RelayAck）全删。

server.rs：删 6 个旧 handler + 3 个辅助函数（forward_dkg/sign/connect_to_coordinator）
+ 5 个结构体字段（sessions/sign_runs/is_coordinator/forward_tls_config/auth_token）——
**impl trait 块同步只留 Cg* 方法**（R2 关键：proto RPC 删后 trait 只要求 Cg* 方法，
不再需要 unimplemented! 占位）。

### 2.2 Rust 侧协同改动（v1 遗漏，R2 补齐）

- `mpc-engine/src/lib.rs`：删 5 个 `pub mod`（aggregate/dkg/sign/distributed/gg20）
- `mpc-engine/src/main.rs`：删 `is_coordinator`/`forward_tls_config` 计算与传参
  （with_distributed_config 缩为 with_party_id）
- `mpc-engine/src/persistence.rs`：删 GG20 持久化（persist_session/load_session/
  persist_my_share/load_my_share/decrypt_my_share/MyShareRecord/SharedKeysSerde）
  ——保留 NXC1 信封共享基础设施 + cggmp blob API
- `mpc-engine/src/session.rs`：SessionManager 是 GG20 身份绑定——保留（CGGMP21
  恢复 E2E 需要 session 幂等），但删 GG20-only 注释

### 2.3 Java 侧（删 8 文件 + 重写 3 编排文件 + 同步 4 DTO）

**删除（旧 SPI 实现 + 测试）**：

| 文件 | 类型 |
|---|---|
| `GrpcMpcCryptoEngine.java` | main，GG20 旧 SPI 实现 |
| `GrpcMpcCryptoEngineTest.java` | test |
| `GrpcMpcCryptoEngineTlsConfigTest.java` | test |
| `SignRequestTest.java` / `DkgRequestTest.java` / `AggregateRequestTest.java` | test |
| `MpcEndToEndTest.java` / `MpcMultiHostEngineTest.java` / `MpcMultiHostTlsTest.java` | test |

**重写（关键——Java SPI 用 Cg* 原生接口重建）**：

| 文件 | R2 处置 |
|---|---|
| `MpcCryptoEngine.java` | **接口本身保留**但方法签名改为 `Cg*` DTO（`CgStartKeygenRequest` 等）——或直接由 `CggmpMpcCryptoEngine` 提供 Cg* 原生方法（见下） |
| `DefaultMpcService.java` | 重写为直接调 `CggmpMpcCryptoEngine` 的 Cg* 方法（keygen→aux→assemble→sign→verify 全用 `Cg*Request/Response`） |
| `ColdWalletMultiSigService.java` | 删 `selectActiveEngine` + `MpcCryptoEngine` 字段 + `cggmpEnabled` flag，只持有 `CggmpMpcCryptoEngine`（单路径） |
| `MpcSigner.java` / `MpcSignatureAggregator.java` | 适配 Cg*（aggregate 语义——CGGMP21 sign 直接产 r/s，无 partial→aggregate 两步） |
| `CggmpMpcCryptoEngine.java` | 从"SPI 适配层"改为 Cg* 原生入口（去掉 DkgRequest 桥接） |

**DTO 同步**：`DkgRequest/DkgResponse/SignRequest/SignResponse/AggregateRequest/AggregateResponse`
6 个 Java DTO 文件删除（与 proto 同步）。

### 2.4 测试侧协同

- `MultiNodeMpcMockTest`（需 Nacos 外部设施）——检查是否用 GG20 符号，是则调整
- `MockMpcCryptoStubFactory` / `DefaultMpcServiceTest`——按 Cg* 重写 stub
- `MpcKeyShare/MpcKeyGeneration`（生产编排）——随 DefaultMpcService 一起迁移 Cg*

## 3. 实施顺序（单批原子，CI 一次验证全链）

```
1. proto 删 6 RPC + 10 DTO（Rust + Java 同时）
2. Rust：删 6 src + 4 依赖 + lib.rs/main.rs/persistence.rs/server.rs
3. Java：删 8 文件 + 重写 3 编排文件 + 删 6 DTO
4. 单 commit 推送 → CI 全链验证（Build&Test / 集群 E2E / kind 冒烟）
```

## 4. 风险与缓解（R2 强化）

| 风险 | 等级 | 缓解 |
|---|---|---|
| Java 编译断级联（v1 5 轮教训） | 高 | 单批原子 = 断也是整体断、一次修到位；CI 编译错误全量暴露 |
| `MultiNodeMpcMockTest` 用 GG20 符号 | 中 | 前置 grep 已列出，实施第一步先确认其符号依赖 |
| `DefaultMpcService` 语义重接错（aggregate 变 noop） | 中 | CGGMP21 sign 直接产 r/s——保留 r/s 拼 `SignResponse` 的语义桥（v1 的 CggmpMpcCryptoEngine 已实现），聚合层改 noop |
| 覆盖率门禁（signing-service 0.36）被删测试拉低 | 中 | 删 7 个测试后覆盖下降——诚实调门禁（R2 含此步） |
| 回滚成本 | 低 | 37 文件删除，git history 全保留 |

## 5. 工作量（37 文件实证修正）

**3-5 天**（v1 误估 1.5-2 天）。拆：
- Rust 侧清理 1-1.5 天（已知死代码 + Cargo 依赖）
- Java SPI 重写 1.5-2 天（DefaultMpcService + ColdWallet + MpcSigner 语义适配）
- proto 同步 + 测试调整 + 门禁调 1 天
- CI 全链回归 + 收敛 0.5-1 天

## 6. 明确不阻塞

当前 GG20 路径**活着但生产不调用**（`cggmpEnabled` 默认 false→CGGMP21，
GG20 仅经 `GrpcMpcCryptoEngine` 供 DefaultMpcService 回退）。因此退役是
"消除维护负担 + 缩攻击面"的**结构性改善**，不修复任何现存 bug、不解除
任何阻塞。**收益纯属长期**，短期无用户可感知变化。

## 7. 用户决策点

本 R2 是**纯设计文档**（未动任何代码）。审核重点：
1. 影响面（37 文件）是否可接受——需要专门开窗期
2. `MpcCryptoEngine` 接口处置（保接口改 Cg* 签名 vs 删接口直接持
   `CggmpMpcCryptoEngine`）——我倾向后者（减少一层抽象，CGGMP21 单路径
   无需 SPI 多态）
3. 是否值得为"长期结构性收益"投入 3-5 天高风险重构——替代：保持双栈
   并存（当前无害），把产能留给其他有用户可感知收益的功能

**若审核通过** → 按 §3 单批开工；**若延后** → GG20 退役冻结，双栈并存
（当前 CI 全绿、无功能缺陷），产能转向其他方向。