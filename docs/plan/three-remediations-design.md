# 三项修缮设计（2026-08-31，交付前审计后续）

## 设计一：MPC 真分散式部署（v2.2.0 架构级）

### 1. 现状（勘察实证）

- `dkg.rs:114` `run_keygen(t, n)` 一次生成**全部 n 方** Paillier 密钥与份额，协调器进程持有全量
- `gg20.rs:59-79` DkgSession 同时存 `party_keys`（全部 Paillier 解密私钥）+ `shared_keys`（全部 n 方私钥份额）
- `dkg.rs:132-147` `extract_private_share` 跨方拒绝后回退分支直接返回 `shared_keys[party_index]`（协调器模式）
- `sign.rs:99` 签名方硬编码 `(0..=threshold)` 前 t+1 方，单进程跑完按请求方分发
- multi-party-ecdsa 0.8.1 底层**已具备**交互式能力（勘察）：`Keygen` 状态机 4 轮（Round0-4 + LocalKey）、`OfflineStage` + `SignManual`（一轮式签名）、`StateMachine` trait（proceed/gmap_queue）、examples 有 sm_client 消息循环范式

### 2. 目标架构

**逐步演进、不推倒重来**——分两阶段，本次落地阶段一：

**阶段一（本次）：份额隔离 + 协调器仅编排消息（不持密钥）**
1. **交互式 DKG**：各节点本地持有自己的 Keygen 状态机（Keygen::new(i,t,n)），协调器只转发 `Msg<RoundN>` 广播消息——**任何一方不再接触他方份额**
2. **本方份额存储**：DKG 完成后每方仅持久化自己的 `LocalKey`（含本方份额+公共承诺），清除全量 party_keys/shared_keys
3. **签名OfflineStage 分布式化**：sign 阶段各方持 OfflineStage 状态机，同样消息转发式
4. **聚合绑定会话公钥**：Aggregate 用 DKG 全体一致的 `LocalKey.public_key`（各方本地一致）验签，不再信任调用方传入的 public_key（修复 aggregate.rs:99-112）

**阶段二（后续，本次不做）**：round-based 异步 runtime（gateway/async session）、份额 threshold 分发、KMS 密钥源——README 已标注为 v2.2.0 后续

### 3. 实施方案（阶段一细节）

新文件 `src/distributed.rs`（约 700 行）：
- `DistDkgState`：包装 `Keygen` 状态机 + `proceed_round` 驱动 + 消息收发（gRPC 转发）
- `DistSignState`：包装 `OfflineStage` + `SignManual`
- 协调器节点（party_index=0）额外持有 `MessageRelay`：只按轮次转发（**不解密、不落盘消息内容**）
- `DkgSession` 存储改造：新 `LocalKeyStorage`（存本方 LocalKey）替代全量 DkgSession；旧格式启动时检测并拒绝（不静默迁移——安全迁移需专项）

proto 增加（`mpc.proto`）：
```
message DistDkgMessage { string session_id; uint32 round; bytes payload; }  // 转发载荷
rpc RelayDkgMessage(DistDkgMessage) returns (Empty);  // 协调器转发
```
DKG 流程（以 3 节点 2-of-3 为例）：
1. 任意方调 `Dkg` RPC（含 peer_endpoints）→ 本方构造 Keygen::new(my_i,t,n)，进 Round0
2. 各方向协调器发 `RelayDkgMessage`（广播自己轮次输出）→ 协调器转发给其他方
3. 各方收到同轮消息 → `proceed_round` → 下一轮输出继续转发
4. Round4 完成 → 各方本地 LocalKey（**全体一致的 public_key 但只有本方份额**）
5. 会话表记 DkgReady + 落盘本方 LocalKey

Sign 流程：同构（OfflineStage 预计算分布式化 + SignManual 单轮）。

### 4. 自审（风险与对策）

| 风险 | 对策 |
|---|---|
| multi-party-ecdsa 消息类型（Round0-4/OfflineM）需序列化跨 gRPC | 都是 serde::Serialize（勘察 state_machine/keygen/rounds.rs 头部 derive）→ bincode/serde_json 转发可行 |
| 现有 3 节点 E2E（协调器模式）不能断 | 保留现有 dkg.rs 作为 legacy 路径（feature flag `coordinator-mode`），distributed 新路径默认启用，测试双路径并存 |
| 交互式轮次超时 | session 管理器已有 30min 超时回收；relay 加 per-round 60s 超时 |
| 聚合验签公钥来源 | 各方 LocalKey.public_key 全体一致（GG20 协议保证）→ 本地取，不再传参 |
| 兼容 Java 侧 GrpcMpcCryptoEngine | Dkg/Sign RPC 出入口不变（仍是 request→response），内部多轮交互由 Rust 引擎间自行完成，Java 无感 |

### 5. 验证标准

- 新增 `tests/distributed_test.rs`：3 节点真分散式 DKG+Sign+Aggregate（每节点独立进程/独立密钥存储）+ 断言每节点磁盘上**只有本方 LocalKey**（读全部节点 data 目录验证无他方份额）
- 现有 integration_test.rs 全绿（协调器 legacy 路径不回归）
- cargo fmt/clippy/test 全绿

---

## 设计二：SDK 三语言叙事修正（F12 诚实化）

### 1. 现状（审计实证）
- typescript/src/transaction.ts、python/conpay、go/conpay 的 sign/buildContractCall 全 NotImplementedError，Go 的 Broadcast 调 core 不存在的 `nexus_sendRawTransaction`
- 三包名残留旧品牌 conpay/CPAY
- README 宣称"4 语言 SDK"（F12 未达成的虚假繁荣）

### 2. 方案（只改叙事，不补实现——补实现是后续立项）
1. README 模块表 nexus-sdk 行改为：`Java 完整；TypeScript/Python/Go 规划中（骨架占位，核心能力未实现）`
2. README 若有"4 语言 SDK"表述 → 改"Java SDK（另三门语言规划中）"
3. nexus-sdk/README.md（若有）+ 三语言目录各加 STATUS.md 头注（或在现有 README 顶部）：明确 `experimental skeleton — core capabilities NOT implemented`
4. PRD F12 项标注实际状态

### 3. 自审
- 零代码风险；文档-only
- 不删三语言代码（保留骨架供后续补全）
- Go Broadcast 调不存在 RPC 这一点在 STATUS.md 中显式警告，防集成方踩坑

---

## 设计三：git 历史密钥重写（BFG/filter-repo）

### 1. 现状
- 旧 JWT 密钥（`REDACTED-ROTATED-JWT-SECRET` 等）存在 git 历史某 2 个 commit（.gitleaks.toml:83-86 豁免维持扫描绿）
- 密钥已轮换失效（JWTUtil 改 @Value 注入），但历史 blob 永久可读

### 2. 方案（filter-repo 替换文本，非 BFG——更可控）
1. 本地备份：`git clone --mirror` 到 `_backup/NexusChain.git`
2. 替换：`git filter-repo --replace-text` 规则文件把两个密钥字符串替换为 `REDACTED-ROTATED`（保留 commit 结构，只改 blob 内容）
3. 同步清理 .gitleaks.toml 里的历史 commit 豁免（重写后 34ca4ea/4670693 的 SHA 变化 → 豁免自然失效删除）
4. 强推：`git push --force origin master`（远端仅 master 分支，PR 无——用户确认过直接推 master 的工作模式）
5. 验证：gitleaks 全历史扫描 0 命中 + CI 双 workflow 绿 + 代码无 REDACTED 泄漏到工作区文件

### 3. 风险与自审
| 风险 | 对策 |
|---|---|
| 不可逆历史改写 | 镜像备份保留 + 改写前记录 `git rev-parse HEAD` |
| 协作者需重新 clone | 该仓库当前唯一活跃提交者是你本人（近期全部提交 Levango7）→ 影响面 1 |
| force-push 保护规则 | 检查 master 是否有 branch protection；若有需先临时放开（gh api 查） |
| 替换字符串误伤 | 只替换 2 个精确密钥字面量（长且唯一），diff 验证只有预期文件变化 |
| CI 在新历史上重跑 | filter-repo 会改全部 commit SHA → CI 全新触发一轮（预期内，最终须全绿） |

### 4. 顺序
先做设计一、二（正常提交），**最后**做历史重写——避免重写后又有新提交导致 SHA 再次漂移。

---

## 执行顺序
1. SDK 叙事修正（10 分钟，先行提交）
2. MPC 分散式（核心工程，~数小时，E2E 验证后提交）
3. 历史密钥重写（收尾，强推 + CI 确认）
