# S4 MPC 安全修复变更摘要

> 日期：2026-09-04
> 范围：`mpc-engine`（Rust 密码学引擎）
> 分支：`feat/mpc-cggmp21-migration-20260901`
> 对应审计项：复查报告 S4（MPC 引擎两项：session_id 路径穿越 + 聚合验签公钥冒用）

## 一、修复背景

审计报告 S4 指出可信协调器路径（`dkg.rs`/`sign.rs`/`aggregate.rs`/`persistence.rs`）存在两个安全问题，本次修复前逐一复核属实：

1. **S4-a（路径穿越）**：Dkg RPC 把调用方原始 `session_id` 直接传入 `persist_session`，持久化层用 `format!("session-{}.json", session_id)` 无净化拼接文件名。含 `../`、盘符冒号（`C:\`）、UNC 前缀（`\\server`）或路径分隔符的 session_id 可**穿越会话目录逃逸写任意路径**。`load_session`（sign.rs 重启恢复用）与 `remove_session`（任意文件删除）同样暴露。
2. **S4-b（验签公钥冒用）**：Aggregate RPC 用**调用方传入的** `req.public_key` 对聚合签名做最终 secp256k1 验证。攻击者可用自控公钥 `P_a = x_a·G` + 自控部分份额构造"验签通过"的假签名——产出的签名对 DKG 聚合公钥毫无意义，验签与 DKG 产物脱钩（信任根基断裂）。`SignCache` 原结构不含聚合公钥，引擎侧无从校验。

> 注：v2.2.0 分散式新路径（`distributed.rs`）此前已自带 `sanitize_session_id` 与本地 `LocalKey.public_key` 验签绑定；本次修复把**协调器旧路径**补齐到同一安全水位，并顺带消除两处净化逻辑的独立维护。

## 二、S4-a 修复：persistence 层根治路径穿越

**根治点选在持久化层**（一处修复覆盖 persist/load/remove 全部入口，dkg.rs/sign.rs 调用点零改动）：

| 文件 | 变更 |
|---|---|
| `src/persistence.rs` | 新增共享 `sanitize_session_id`（`pub(crate)`，仅保留 `[A-Za-z0-9-_]`，其余替换 `_`）；`session_path`/`my_share_path` 统一净化后拼接 |
| `src/distributed.rs` | 删除私有同名实现，改为委托 `crate::persistence::sanitize_session_id`（防两处漂移） |

**净化策略**：产物只含安全字符集，`session_dir().join(sanitized)` 结构性保证落在会话目录内的单文件名——`../` 逃逸、Windows 盘符、UNC 前缀、嵌套分隔符全部封堵。内存缓存仍按原始 id 键控（RPC 契约不变），盘文件名仅作持久化载体，读写删走同一净化函数闭环一致。

## 三、S4-b 修复：验签公钥绑定 DKG 聚合公钥

信任链条重建：**Sign 阶段引擎自 DKG 会话写入 → Aggregate 只信任缓存值 → 请求公钥仅作一致性校验**。

| 文件 | 变更 |
|---|---|
| `src/gg20.rs` | `SignCache` 新增 `aggregate_public_key: Point<Secp256k1>` 字段（公钥非秘密材料，无需 zeroize） |
| `src/sign.rs` | 唯一构造点（全库核实仅此一处）从 `session.y_sum.clone()` 写入绑定公钥 |
| `src/aggregate.rs` | ① 聚合前对 `req.public_key` 与缓存绑定值做一致性校验（fail-closed，不匹配直接拒绝且不产出签名）；② 最终验签**只用缓存公钥**，不再解析信任请求值 |

修复后与分散式路径（`LocalKey.public_key` 全体一致）同一信任根基：验签公钥恢复与 DKG 产物的密码学绑定。

## 四、新增回归测试（6 条）

persistence（S4-a）：
- `session_path_never_escapes_session_dir` — 10 种恶意 session_id（`../`、`../../etc/passwd`、`..\..\windows`、`C:\Users\`、UNC、分隔符、`.`/`..`、保留名）经路径函数产物必须仍在会话目录内且文件名不含分隔符
- `persist_load_remove_round_trip_with_traversal_session_id` — 恶意 id 读写删命中同一净化文件、逃逸目标路径不出现
- `sanitize_session_id_only_keeps_safe_chars` — 字符集白名单逐例断言

aggregate（S4-b）：
- `aggregate_with_correct_public_key_succeeds` — 正常链路（DKG 聚合公钥 + 正确份额）聚合成功，保护既有行为
- `aggregate_rejects_attacker_controlled_public_key` — 攻击者公钥 `x_a·G` fail-closed 拒绝，错误信息含 `public_key mismatch`
- `aggregate_with_correct_key_but_tampered_shares_fails_verification` — 正确公钥 + 篡改份额 → 绑定通过但验签失败，证明绑定后验签真实工作

## 五、验证结果

| 验证项 | 命令 | 结果 |
|---|---|---|
| 引擎全部单测 | `cargo test --bins` | **55 passed / 0 failed**（含 6 条新测试，46 条既有零回归） |
| CGGMP 模拟测试 | `cargo test --features tls --test cggmp_dkg_sim` | **3 passed / 0 failed** |
| 集成测试 target 编译面 | `cargo test --features tls --no-run` | 编译通过（SignCache 加字段无破坏；运行需 live 集群 + `--ignored`，非本次改动引入） |
| 兼容性核查 | 人工全量 | Aggregate 全部 5 处调用点均传 DKG 聚合公钥（见下），绑定校验不破坏任何正常链路 |

**兼容性核查明细**（修复前完成，确认不破坏 Java 主链路）：
- `DefaultMpcService.generateKeyShare` → `wallet.publicKey` 源自 `DkgResponse.getPublicKey()`（DKG 聚合公钥）
- `DefaultMpcService.signTransaction`（遗留编排）/ `ColdWalletMultiSigService.runRealMpcAggregate` → `wallet.getPublicKey()` 同源
- `MpcEndToEndTest`（Java E2E）→ `jointPublicKey`（三方一致性已断言）
- Rust `tests/integration_test.rs` → `dkg_results[0].public_key`

## 六、变更文件清单

```
mpc-engine/src/persistence.rs   # S4-a：共享净化函数 + 双路径接入 + 3 测试
mpc-engine/src/distributed.rs   # S4-a：私有实现改委托共享版
mpc-engine/src/gg20.rs          # S4-b：SignCache 增 aggregate_public_key 字段
mpc-engine/src/sign.rs          # S4-b：构造点从 session.y_sum 写入绑定公钥
mpc-engine/src/aggregate.rs     # S4-b：绑定校验 + 验签改用缓存公钥 + 3 测试
```

## 七、构建环境备注

- 本机 Windows GNU 工具链缺 `dlltool`/`gcc`（rustup self-contained dlltool 可补前者，但 cggmp21 → rug → gmp-mpfr-sys 源码编译链还需 MSYS2 gcc/m4/make，见 Cargo.toml 与 Dockerfile 注释），验证改走 **WSL Ubuntu-24.04**。
- WSL 环境对齐 Dockerfile（rust:1.88 等价 + `libgmp-dev`/`libmpfr-dev`/`m4`）；过程中修正了一次 PowerShell 双引号 `$t` 提前展开导致的"工具链全 OK"假阳性（实际 m4 缺失，正是 GMP configure 失败根因）。
- `tests/integration_test.rs` 为预存的 `--features tls` + live 三节点集群手动测试（文档注明 `--ignored` 触发），本次仅保证编译面通过。

## 八、后续建议（未在本次范围）

- 协调器路径按 Cargo.toml 规划终将退役（CGGMP21 迁移主线），届时 `multi-party-ecdsa` 路径的 S4-b 修复随之自然消亡，分散式路径已有同位防护。
- `MPC_ENGINE_SESSION_DIR` 生产部署建议继续配合 NTFS ACL / 0600 权限（`set_secure_permissions` 已就位）形成纵深防御。
