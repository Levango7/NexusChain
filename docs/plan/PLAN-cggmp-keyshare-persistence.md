# PLAN：CGGMP21 KeyShare 持久化与重启恢复（分布式生产前提）

状态：**已批准实施（2026-09-06）**——含审核修订 3 条（见 §7）
日期：2026-09-05（设计）/ 2026-09-06（修订 + 实施）
前置：D 批 AES 落盘基建（45eb598）、E/I 批 CGGMP21 迁移与集群 E2E、J 批全流水线 E2E

## 1. 问题

CGGMP21 三协议产物目前**只存在于驱动线程内存**：
`CgSession{core_share, aux_info, key_share}`（`mpc-engine/src/cggmp.rs:452-465`）。
进程重启 / K8s Pod 重调度 / PVC 重挂后：

- 已完成的 DKG 份额**全部丢失**，必须三方重新跑完整 keygen+aux；
- 生产 StatefulSet 语义下这是不可接受的——份额即资产，重跑协议意味着
  参与方在线性协调 + 全员同时可用；
- 这是 v2.2.0 阶段三（K 批 K8s 部署）的**硬前置**。

## 2. 已有基建（本设计零新增加密组件）

| 组件 | 位置 | 状态 |
|---|---|---|
| NXC1 AES-256-GCM 加密信封（`MAGIC\|\|version\|\|nonce\|\|ct`） | `persistence.rs:57-230`（D 批） | 成熟，有格式/明文拒收测试 |
| `aes_encrypt_with_version` / `aes_decrypt_with_version` | `persistence.rs` | pub(crate) |
| KeyShare 序列化（serde JSON + `sanitize_for_disk` 清洗 crt/multiexp） | `cggmp.rs:74-100` | E 批已验证 round-trip + validate |
| IncompleteKeyShare 序列化 | `cggmp.rs:105-112` | 已实现 |
| 每节点会话目录 | `MPC_ENGINE_SESSION_DIR` env | start-mpc-cluster.sh / 集群测试均已接线 |
| LocalKey 持久化范本（GG20 路径，D 批） | `distributed.rs:426-490` | persist/load + fail-closed 模式照抄 |

## 3. 设计

### 3.1 持久化范围（阶段一，务实裁剪）

| 产物 | 落盘时机 | 理由 |
|---|---|---|
| `core_share`（IncompleteKeyShare） | keygen 完成即落盘 | DKG 是三方协作、成本最高的一步，完成即固化 |
| `key_share`（KeyShare，sanitize 后） | assembleShare 完成即落盘 | 长期份额，sign 的输入 |
| `aux_info` | **不落盘**（阶段一） | serde 支持待验证；aux 中断的重跑成本 = 三方一次 aux 协议（秒级），无需重跑 DKG |
| sign 状态机 | 不落盘 | 瞬态，重启后重跑 sign 即可（J 批已证重跑可行） |

### 3.2 文件布局与格式

```
$MPC_ENGINE_SESSION_DIR/cggmp/{session_id}/incomplete.bin   ← core_share
$MPC_ENGINE_SESSION_DIR/cggmp/{session_id}/keyshare.bin     ← key_share
```

格式 = D 批同一 NXC1 信封（`NXC1 || version LE || nonce || ciphertext`），
密钥 = `MPC_STORAGE_KEY`（hex 32B），密钥版本随信封记录（多密钥切换时
无需迁移，与 C 组 TODO 的密钥轮换路线天然兼容）。

### 3.3 新 API（`persistence.rs`，签名对齐 distributed.rs 范本）

```rust
pub fn persist_cggmp_incomplete(session_id, party_index, base_dir,
    core: &IncompleteKeyShare<Secp256k1>, key: &[u8; KEY_LEN], key_version: u32) -> eyre::Result<()>;
pub fn load_cggmp_incomplete(session_id, party_index, base_dir, key) -> eyre::Result<Option<IncompleteKeyShare<...>>>;
pub fn persist_cggmp_key_share(..., share: &KeyShare<Secp256k1>, ...) -> eyre::Result<()>;
pub fn load_cggmp_key_share(..., key) -> eyre::Result<Option<KeyShare<Secp256k1>>>;
```

- encode 侧强制走 `sanitize_for_disk`（crt = Paillier 私钥级敏感，绝不落盘）；
- decode 侧走 `Validate::validate`（E 批已证缺省字段可重载）；
- 加载后 `precompute_crt` / `precompute_multiexp_tables` 按需重建（上游 API），
  或首次 sign 时懒重建——实施时按 validate 行为定，二选一都 fail-closed。

### 3.4 接线点（`cggmp_state.rs` / `server.rs`）

1. **写路径**：keygen 完成（`CgPumpKeygen` 返回 finished 时）→ persist incomplete；
   assembleShare 成功 → persist keyshare。落盘失败 **fail-closed**：协议产物
   保留在内存、RPC 返回错误（调用方可重试），不静默吞掉。
2. **读路径（幂等守卫扩展）**：
   - `CgStartAux`：若 keyshare.bin 已存在 → 直接返回成功（已完成态，跳过 aux）；
   - `CgStartKeygen`：若 incomplete.bin 已存在 → 加载进 registry 的 core_share
     （避免重跑 DKG；配合三方协商，任一方已持有即整组跳过——**需协调方约定**，
     阶段一实现为"每方各自幂等"，组级跳过由调用方/协调器判断）；
   - `CgAssembleShare`：若 keyshare.bin 已存在 → 直接成功；
   - `CgStartSign`：从 registry 取 key_share，miss 时尝试 load_cggmp_key_share。

### 3.5 测试计划

1. 单测：round-trip（encode→encrypt→decrypt→decode→validate）；
2. 单测：密文格式（NXC1 魔数开头、非明文 JSON——照抄 distributed.rs:656-690 的断言）；
3. 单测：篡改密文 → 解密失败 fail-closed；
4. 单测：错误密钥 → 解密失败；
5. E2E（Rust 集成测试，复用集群）：keygen→落盘→**新建 registry 模拟重启**→
   load→assemble→sign→verify 与 J 批同断言；
6. Java 侧不新增测试（行为经 Rust E2E + 幂等守卫覆盖，Java 集群 E2E 由
   CgStartAux 幂等路径自然回归）。

## 4. 风险与开放问题

| 风险 | 等级 | 缓解 |
|---|---|---|
| crt/multiexp 重载后未重建导致 sign 失败 | 中 | E2E 5 覆盖"load→sign"路径；失败即 fail-closed 报错，不会产错签 |
| 三方"组级跳过 DKG"的协调语义 | 中 | 阶段一做每方幂等，组级由协调器判断——文档明示边界，不隐式猜测 |
| session_id 目录清理策略（长期累积） | 低 | 沿用现有 sessions 目录治理；阶段一不做 TTL |
| `MPC_STORAGE_KEY` 单密钥限制 | 低 | NXC1 已带版本号，C 组多密钥落地时零迁移 |

## 5. 工作量

约 2-3 天：persistence API + 单测 1 天；接线 + E2E 1 天；回归 + 文档 0.5-1 天。

## 6. 明确不做（阶段一）

- aux_info 落盘（serde 验证后可在阶段二补）
- 份额轮换 / resharing（cggmp24 0.7 范围）
- 跨节点份额备份（违背门限语义，永不实现）

## 7. 审核修订（2026-09-06 批准，实施已合入）

1. **不对称恢复显式 fail-closed**：单节点只保证**自身状态一致性**——
   盘上密文解码失败（篡改/截断/换密钥）一律硬错误（拒绝服务），
   绝不静默跳过或伪造状态；"keyshare 存在但 incomplete 缺失"等组内
   不一致同样显式报错。**组级一致性（三方是否同状态）是调用方契约**：
   同一 session 的三方各自幂等，协调方（Java 编排层）负责以同 session
   重试或以新 session 全组重跑——节点侧不猜测他方磁盘状态。
2. **MPC_STORAGE_KEY 持久性前提显式化**：本地路径 = nodeN.json 内嵌
   storage_key（已存在则复用，start-mpc-cluster.sh 现状）；K8s 路径 =
   helm mpc-engine secret.yaml 注入持久 Secret（K 批对齐项）。
   引擎侧语义：`MPC_STORAGE_KEY` 未设置 → 持久化功能关闭（WARN 一次，
   协议照常——与今日行为兼容，测试环境零负担）；已设置但写盘 IO 失败
   → **fail-closed RPC 错误**（绝不静默丢份额）。
3. **测试计划补截断用例**：GCM 完整性校验显式验证（截断文件解密必败），
   与篡改、错密钥并列。
