# PLAN-001：GG20 旧路径退役（CGGMP21 独占）

状态：**设计稿，待审核；未实施**（2026-09-17 审计核实：默认运行态仍为 GG20 可信协调器路径）

> **本文件未描述任何已生效的变更。** 2026-09-17 交付前审计确认：
> `mpc.engine.cggmp-enabled` 默认 false、`mpc.engine.distributed-mode` 默认 false，
> `ColdWalletMultiSigService.selectActiveEngine()` 在默认配置下返回 GG20 引擎，
> GG20 签名仍在协调进程内一次性执行全部签名方（`mpc-engine/src/sign.rs`）。
> 即：**GG20 退役尚未开始**；本文件只是待审核的退役方案，不得作为
> "已切换 CGGMP21 / 已分布式"的证据引用。
日期：2026-09-07
前置：D 批 LocalKey 落盘（ac0cbc1 之前的 45eb598）、E/I 批 CGGMP21 迁移（7895c03 + I 批）、J 批全流水线 E2E（fe4fd1d）、⑤批 KeyShare 持久化（ac0cbc1）、K 批 K8s 部署（70c1ce1）

## 1. 目标

把 `MpcCryptoEngine` SPI 的双实现（GG20 + CGGMP21）收缩为**单 CGGMP21 实现**。`selectActiveEngine` 的 `mpc.engine.cggmp-enabled` 配置开关也同步删除——路径选择不再是用户决策，而是代码层面的唯一路径。

## 2. 影响面（grep 实证，2026-09-07）

### 2.1 Rust 引擎侧

| 文件 | 状态 | 说明 |
|---|---|---|
| `mpc-engine/src/gg20.rs` | **整文件删除** | GG20 密码学核心（KeyShare、SignCache、Zeroize 包装） |
| `mpc-engine/src/dkg.rs` | **整文件删除** | GG20 DKG handler |
| `mpc-engine/src/sign.rs` | **整文件删除** | GG20 sign handler |
| `mpc-engine/src/aggregate.rs` | **整文件删除** | GG20 aggregate handler |
| `mpc-engine/src/distributed.rs` | **整文件删除** | GG20 distributed DKG（cggmp_state.rs:836 仅一行注释引用） |
| `mpc-engine/tests/integration_test.rs` | **整文件删除** | 6 个 GG20 cluster E2E 测试（需本地集群，与 kind 冒烟重叠） |

### 2.2 Java 侧

| 文件 | 操作 | 说明 |
|---|---|---|
| `nexus-signing-service/src/main/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngine.java` | **整文件删除** | GG20 路径的 SPI 实现，调旧 RPC |
| `nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngineTest.java` | **整文件删除** | GG20 SPI 单元测试（57 个测试） |
| `nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/SignRequestTest.java` | **整文件删除** | GG20 SignRequest DTO 测试 |
| `nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngineTlsConfigTest.java` | **整文件删除** | GG20 mTLS 配置测试 |
| `nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcEndToEndTest.java` | **整文件删除** | GG20 E2E 测试 |
| `nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcMultiHostEngineTest.java` | **整文件删除** | GG20 multi-host 测试 |
| `nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcMultiHostTlsTest.java` | **整文件删除** | GG20 multi-host TLS 测试 |
| `ColdWalletMultiSigService.java` | **简化** | 删 `selectActiveEngine` / `MpcCryptoEngine` 注入（GG20 分支）、`cggmpEnabled` flag、Engine 描述字符串。保留 `CggmpMpcCryptoEngine` 单引擎 |
| `DefaultMpcService.java` | **简化** | 删注释中"GrpcMpcCryptoEngine 通过 gRPC 调用 Rust
 multi-party-ecdsa 引擎"（已不准确）+ `@see GrpcMpcCryptoEngine` 引用 |
| `CggmpMpcCryptoEngine.java` | **保留** | SPI 实现类**保留**（SPI 契约的 CGGMP21 适配，名字误导但语义正确） |
| `CggmpMpcCryptoEngine` import 路径 | **调整** | `mpcCryptoEngine` 字段类型从 `MpcCryptoEngine` 改为 `CggmpMpcCryptoEngine`（去 SPI） |
| `MpcSigner.java`、`MpcSignatureAggregator.java` | **保持** | 只删 TODO 注释中的 GG20 引用 |

### 2.3 Proto 接口（critical decision）

`DkgRequest / SignResponse / AggregateRequest / AggregateResponse / DkgResponse` **保留**！原因：
- `CggmpMpcCryptoEngine` 仍实现 `MpcCryptoEngine` SPI、SPI 入口参数/返回就是这 3 个 DTO
- `ColdWalletMultiSigService` / `DefaultMpcService` 调用 SPI 时用这些 DTO
- SPI 内部委托给 `MpcCggmpOrchestrator`（用 `Cg*Request` 实际发请求）

退役 5 个 Rust 文件后，proto 里这 3 个 DTO 退化为"Java SPI 内部数据结构"——但仍需保留（H 批设计就是这么做的）。

**唯一可删的 proto**：`Dkg` / `Sign` / `Aggregate` 三个 RPC + 它们的 server handler（`server.rs` 里 `async fn dkg(...)` / `sign` / `aggregate`）。这些 RPC 服务端已无 handler 实装（之前删了 GG20 路径时一并删的——但 Rust 文件仍在，所以 handler 也在）。看 server.rs 验证。

## 3. 文件操作清单

### 3.1 删除

```
mpc-engine/src/gg20.rs
mpc-engine/src/dkg.rs
mpc-engine/src/sign.rs
mpc-engine/src/aggregate.rs
mpc-engine/src/distributed.rs
mpc-engine/tests/integration_test.rs
nexus-signing-service/src/main/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngine.java
nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngineTest.java
nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/SignRequestTest.java
nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngineTlsConfigTest.java
nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcEndToEndTest.java
nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcMultiHostEngineTest.java
nexus-signing-service/src/test/java/org/nexus/signing/mpc/MpcMultiHostTlsTest.java
```

### 3.2 修改

- `mpc-engine/src/lib.rs` — 删 `pub mod gg20; pub mod dkg; pub mod sign; pub mod aggregate; pub mod distributed;`
- `mpc-engine/src/server.rs` — 删 `dist: DistRegistry` 字段 + `Dkg` / `Sign` / `Aggregate` 三个 RPC handler（保留 `Cg*` 全部 handler）
- `mpc-engine/src/cggmp_state.rs:836` — 改注释"与 distributed.rs 同构"指向"继任者"或删除
- `mpc-engine/src/cggmp.rs` — 删 6 处旧 `Encode/Decode 跨协议` 注释中对 GG20 的引用（如有）
- `mpc-engine/Cargo.toml` — 检查依赖：若 `multi-party-ecdsa = "0.8.1"` / `curv-kzen` 仅 GG20 路径用，则从依赖移除（`toml` 检查）
- `nexus-signing-service/src/main/java/org/nexus/signing/mpc/crypto/MpcCryptoEngine.java` — **保留**（SPI 接口，`CggmpMpcCryptoEngine` 仍实现它）
- `nexus-signing-service/src/main/java/org/nexus/signing/mpc/ColdWalletMultiSigService.java` — 删 GG20 注入、简化 `selectActiveEngine` 为 `cggmpEngine` 单字段
- `nexus-signing-service/src/main/java/org/nexus/signing/mpc/DefaultMpcService.java` — 改注释
- `nexus-signing-service/src/main/java/org/nexus/signing/mpc/cggmp/MpcCggmpClient.java:413` — "与 GrpcMpcCryptoEngine 一致" 注释改"与 MpcCryptoEngine SPI 契约一致"

### 3.3 不动

- `nexus-sdk/common/protobuf/conpay.proto:183` 的 `SignRequest` — 不同 proto（SDK 服务端的 conpay 服务，非 mpc ），名字撞但 namespace 隔离
- `MpcCggmpClient` 全部 / `CggmpMpcCryptoEngine` 全部 / `MpcCggmpOrchestrator` 全部 / `CggmpMpcE2EClusterTest` 全部 — CGGMP21 路径，保持
- proto 的 `DkgRequest` / `SignRequest` / `AggregateRequest` DTO 定义 — SPI 桥接仍需

## 4. 实施顺序（最小风险滚动）

### 第 1 步：删除 Rust 引擎侧（5 个 src + 1 个 test + Cargo.toml + lib.rs + server.rs）

```bash
git rm mpc-engine/src/{gg20,dkg,sign,aggregate,distributed}.rs
git rm mpc-engine/tests/integration_test.rs
# 编辑 mpc-engine/src/lib.rs 删 mod 声明
# 编辑 mpc-engine/src/server.rs 删 dist 字段 + 3 个 RPC handler
# 检查 Cargo.toml 移除 multi-party-ecdsa 等仅 GG20 使用的依赖
cargo build --release --features tls   # 必须无错
cargo clippy --all-targets --features tls -- -D warnings
cargo test --features tls --test cggmp_threshold_e2e
cargo test --features tls --test cggmp_rpc_e2e
cargo test --features tls --test cggmp_persistence_recovery
```

预期：编译通过、3 个 CGGMP21 测试全绿。

### 第 2 步：删除 Java 旧实现 + 7 个测试

```bash
git rm nexus-signing-service/src/main/java/org/nexus/signing/mpc/crypto/GrpcMpcCryptoEngine.java
git rm nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/{GrpcMpcCryptoEngineTest,SignRequestTest,GrpcMpcCryptoEngineTlsConfigTest}.java
git rm nexus-signing-service/src/test/java/org/nexus/signing/mpc/{MpcEndToEndTest,MpcMultiHostEngineTest,MpcMultiHostTlsTest}.java
# 编译：删文件后先看哪个 src 引用 GrpcMpcCryptoEngine
git grep -nE "GrpcMpcCryptoEngine" -- nexus-signing-service/src/
# 修 ColdWalletMultiSigService + DefaultMpcService 注释
```

### 第 3 步：CI 验证

```
ci.yml 的 mpc-java-cluster-e2e (kind 不依赖 GG20) → 应全绿
ci.yml 的 mpc-kind-smoke (kind 3 节点，引擎二进制已无 GG20 依赖) → 应全绿
ci.yml 的 Build & Test (cargo build + cargo test --features tls 套所有 CGGMP21 测试) → 应全绿
```

### 第 4 步：精简 ColdWalletMultiSigService

把 `selectActiveEngine` + `MpcCryptoEngine mpcCryptoEngine` 字段 + `cggmpEnabled` flag 全部删掉，直接持有 `CggmpMpcCryptoEngine`。约 50 行代码精简。

## 5. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 误删被隐藏的 RPC 调用方 | **中** | grep 已穷举 6 个生产主路径文件 + 7 个测试文件 + 2 个 SPI 类（已逐一确认 `CggmpMpcCryptoEngine` 替代 `GrpcMpcCryptoEngine`） |
| `integration_test.rs` 删了但 CI 还引用 | 低 | grep 确认无任何 CI 步骤调此测试；它在 `cargo test` 默认范围下运行但本地无集群必红，是历史债务 |
| Cargo.toml 依赖移除不彻底（编译仍过但二进制臃肿） | 低 | 删除文件后 `cargo build` 必报错强制清理；不依赖的 crate 自然被 cargo tree 警告 |
| `CggmpMpcCryptoEngine` 类名误导（实际是 SPI 适配层而非纯 CGGMP21 内部） | 低 | 文档明示，不重命名（重命名会破 SPI bean 名） |
| 误删 cargo 依赖破坏其他模块 | **中** | step 1 末尾跑 `cargo build --release --features tls` 必须通过才能 commit；本地无 JDK 17 环境（今日早些时候被卸载）→ 全部依赖 CI 编译验证 |
| 回滚成本 | 低 | git history 永久保留；merge 冲突而非数据丢失 |

## 6. 不可逆性

- 5 个 Rust 文件 + 13 个 Java 文件删除 = ~4000 行代码消失
- proto 的 3 个 RPC handler 删除 = 不可逆的 wire format 变化
- **但 git history 永久保留**——任意 commit 可恢复；本批 1.5 天 / 选项 6 之前完成
- 回滚路径：若发现生产确实有 GG20 隐式调用方（极不可能，grep 已穷举）→ revert commit + 零数据丢失

## 7. 成本估算

- **1.5-2 天**：grep 已完成 0.5 天 + Rust 删/改 0.5 天 + Java 删/改 0.5 天 + CI 验证回归 0.5 天
- 与"收益-风险比"对照：删 4000 行代码、移除一个 0.8.1 旧密码学 crate 的所有攻击面，0.5 天内全部回归验证可绿

## 8. 完成后状态

- 密码学依赖图：`multi-party-ecdsa 0.8.1` + `curv-kzen 0.9`（GG20 路径）→ **移除**；仅留 `cggmp21 0.6.3`（CGGMP21 路径）
- proto 表面：5 个 RPC → 2 个 RPC（Cg*），DTO 减半
- Java 代码量：~4000 行 → ~2500 行（-37%）
- TODO 债务：旧路径 ZK proof 3 处（#47 分诊 D 组）自动消除
- sign 阶段二设计空间解锁：cggmp21 sign relay 现成可用
