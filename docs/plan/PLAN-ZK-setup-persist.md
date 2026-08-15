# 方案：ZK setup 持久化 + 非演示证明模式

- **状态**：Approved + 已实施（2026-08-15）
- **日期**：2026-08-15
- **前置**：`bridge.rs` 动态电路 + 真实 Groth16 已验证；当前为**自证明模式**（setup 每次随机 seed 42 + prove/verify 同请求内）
- **目标**：① 确定性/持久化 setup（同电路同 pk/vk，跨请求复用）② prove/verify 分离（证明可持久化、独立验证）

---

## 一、现状问题（代码已确认）

```
bridge_verify(json):                       ← 自证明模式
  setup: generate_random_parameters(seed=42)  ← 每次随机，pk/vk 不持久化
  prove: 同请求内生成证明
  verify: 同请求内验证
问题:
  1. setup 每次重建（SRS 不跨请求复用，浪费 + 非确定）
  2. prove/verify 不可分离（无证明持久化，验证方无法独立验证）
  3. 无"可信设置"语义（正式 ZK 需 setup 一次 → vk 公开 → prove/verify 分离）
```

**能力确认**：`ark-serialize 0.4.2`（CanonicalSerialize/CanonicalDeserialize）已在依赖树
（ark-groth16 → ark-ec → ark-serialize）——pk/vk/proof 均可序列化持久化。

## 二、方案设计

### 核心：电路指纹 → 确定性 setup → 持久化

```
电路指纹 = hash(约束结构)（num_public/num_private/constraints 序列化 → SHA-256）
setup(pk, vk) 按指纹幂等：
  磁盘已存在（mpc-sessions 风格目录 groth16-setup/<fingerprint>/）→ 加载
  不存在 → 确定性 setup（seed = 指纹派生）→ 序列化落盘
```

### API 拆分

| 端点 | 语义 | 载荷/返回 |
|---|---|---|
| `POST /v1/setup` | 按电路指纹幂等 setup，返回 vk（公开）+ setup 状态 | `{circuit_json}` → `{fingerprint, vk_hex, exists: bool}` |
| `POST /v1/prove` | 用持久化 pk + 电路 + witness 生成证明 | `{circuit_json}` → `{fingerprint, proof_hex}` |
| `POST /v1/verify`（改） | 用持久化 vk + 外部证明 + 公共输入验证 | `{circuit_json, proof_hex}` → `{valid, error}` |
| `GET /v1/vk/{fingerprint}` | 获取公开验证密钥（对外发布） | → `{fingerprint, vk_hex}` |

### 序列化格式

```
pk/vk/proof：ark-serialize CanonicalSerialize → 二进制 → hex 编码（传输/落盘）
指纹：SHA-256(约束 JSON canonical)
存储目录：$GROTH16_SETUP_DIR（默认 ./groth16-setup）/<fingerprint>/pk.bin, vk.bin
```

### 确定性（同电路 → 同 pk/vk）

```
seed = SHA-256(指纹) 截断 → StdRng 确定性 → 同一电路任何节点产生相同 SRS
（配合容错：正式部署可改用"可信设置仪式"替换确定性 seed，见风险）
```

## 三、核心改动点

| 文件 | 改动 |
|---|---|
| `bridge.rs` | `circuit_fingerprint(json)`、`load_or_setup(pk)`（磁盘幂等）、`prove_real`、`verify_with_proof` |
| 新增 `setup_store.rs` | 指纹 → 目录管理 + pk/vk 序列化/反序列化 |
| `main.rs` | HTTP 端点拆分（setup/prove/verify/vk） |
| 测试 | setup 幂等（两次调用 vk 一致）、prove→verify 分离闭环、篡改证明拒绝 |

## 四、风险与缓解

| 风险 | 缓解 |
|---|---|
| 确定性 seed = 非可信设置（任何人可重算 SRS） | 正式部署改用可信设置仪式（多方 MPC 或离线安全生成）；确定性 seed 用于开发/测试/演示 |
| pk 体积大（多约束电路） | 二进制 + 磁盘缓存，仅 setup 时生成一次 |
| 指纹碰撞 | SHA-256（约束 canonical JSON），碰撞概率可忽略 |
| 旧接口兼容 | /v1/verify 保留自证明路径（无 proof_hex 时），新增 prove 分离路径 |

## 五、验证标准

```
1. 同电路两次 setup → vk 完全一致（确定性实证）
2. prove 输出 proof_hex → verify(proof_hex + 电路 + 公共输入) → valid
3. 篡改证明 → verify false（真实安全语义）
4. 跨请求（服务重启后）setup 从磁盘加载（持久化实证）
5. 现有自证明测试保持通过（向后兼容）
```

## 六、待审核决策点

1. **确定性 seed 的可信度**：开发/演示用确定性 seed（推荐）vs 正式引入可信设置仪式？
2. **接口形态**：HTTP 端点拆分（setup/prove/verify，推荐）vs 单端点加模式参数？
3. **存储位置**：`$GROTH16_SETUP_DIR`（可配置，默认 ./groth16-setup，推荐）vs 固定路径？
4. **pk 安全**：pk 含 toxic waste（δ 等）——磁盘权限 + 部署隔离（推荐 0700 + 明确警告）vs 不落盘（每次重建）？

请审核并给出决策，通过后实施。

## 实施结果（2026-08-15，已提交）

- setup_store.rs：电路指纹 → 目录持久化 pk/vk（ark-serialize binary、0700 权限、幂等）
- bridge：prove_real / verify_with_proof / setup_public（分离模式）
- HTTP：/v1/setup、/v1/prove、/v1/verify-sep 端点
- 验证：Rust 7 测试全绿 + HTTP 端到端（setup 幂等 vk 一致 / prove→verify-sep 闭环 / 篡改拒绝）

**ZK 长期项全部完成**：可行性 → 服务 → 桥接 → 生产电路 → setup 持久化 + 分离证明模式。

## 可信设置仪式（2026-08-15，✅ 落地）

确定性 seed（开发/演示）之上新增**外部 setup 注入**（替换确定性 seed 的生产路径）：
- `GROTH16_EXTERNAL_SETUP_DIR`：仪式产出目录（load_or_setup 优先加载，跳过确定性生成）
- `/v1/setup-external`：仪式产出（pk/vk hex）经 API 导入（0700/0600 权限）
- `import_external_setup` / `export_pk_hex`：导入/导出工具
- 验证：Rust 8 测试全绿（外部导入→prove→verify 分离闭环，vk 与仪式产出一致）

**生产路径**：仪式产出 pk/vk → 部署到外部目录或 API 导入 → 服务用外部可信 SRS。
完整多方仪式（MPC ceremony 协议）为独立工程，接口已就绪。
