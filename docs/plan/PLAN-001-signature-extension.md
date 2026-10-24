# 协议后续方案：验证人集消息签名化（PLAN-001 签名边界修正提案）

> 背景：F1 修复（PLAN-030，已交付 #191）已实现验证人集载荷的**指纹级转发**（LRU 512 防环、回声两跳收敛、注册表全表回放），但**中继消息本身未签名**（沿用 PLAN-001 信任模型，伪造 validator-set 与修复前同等敞口）。本方案提出签名化路径，作为协议层后续切片（不属于本轮多机基础设施测试范围，已如实登记于 `PLAN-027` §六 边界说明）。

> 当前信任模型（修复前后一致）：广播只发给直连对端（星型拓扑），接收端走 `seedStake → register → upsert` 幂等注册。F1 修复新增转发路径：成功处理 `add/remove` 后按 SHA-256 指纹转发一次（同一载荷每节点最多转发一次）。**签名化目标**：让 `add` 消息本身可验证（防伪造 + 防重放），不改变现有星型拓扑和注册路径。

---

## 一、威胁模型与边界（不夸大）

### 当前敞口（与修复前同等，不增加新风险）
- **伪造 `add`**：任何掌握节点私钥的攻击者可构造任意 `validator-set` 广播消息（含虚假验证人），接收端无法区分真实与伪造（现有 `seedStake` 注册只检查地址格式，不做签名验证）。
- **重放攻击**：同一真实 `add` 载荷被恶意重复广播（F1 的 LRU 512 防环仅防**中继环路**，不防**原始广播重复**——重复广播同一载荷的节点不会被 LRU 拦截，因为 LRU 记录的是"同一节点接收到的同一载荷"，而攻击者可用不同节点重复广播）。
- **篡改风险**：星型拓扑下，A 居中节点可篡改 B/C 的广播内容（但 B/C 可直接与 A 通信，不依赖中间节点转发其广播，篡改影响有限）。

### 签名化目标（不改变拓扑）
不引入新的网络拓扑（仍为星型 + 各节点与 A 双向），只在消息层增加**可验证签名**：
1. **真实性**：接收端可验证 `add` 消息确实由声明的验证人私钥签名（防伪造）。
2. **不可重放**：签名包含唯一 nonce 或 epoch + 序列号（防重放）。
3. **不可篡改**：签名覆盖完整载荷（防中间篡改）。
4. **兼容性**：老节点收到带签名的消息时按非投票载荷丢弃（向后兼容，行为与当前"无签名中继"一致）；新节点接收时做签名验证并继续走幂等注册路径。

---

## 二、设计方案（与现有代码路径逐条对齐）

### 2.1 签名方案选择（Ed25519——直接签名，无预哈希）

| 维度 | 选择 | 理由（与现有代码对齐） |
|------|------|------------------------|
| **算法** | Ed25519（验证人私钥已为 32 字节十六进制字符串，格式兼容） | 现有 `validator-private-key` 配置已为 64 字符十六进制（`bbbb...` / `cccc...`），直接映射为 Ed25519 私钥字节流（32 字节）。无需修改现有密钥生成或存储路径。 |
| **预哈希** | 无（直接对载荷字节签名；Ed25519 内部使用 SHA-512） | 纯 JDK 17 实现（`java.security`），无新外部依赖（JDK 无内置 Blake2b）；避免与 F2/F1 追块路径的 SHA-256 指纹语义混淆；载荷与 F1 指纹转发的 `TRANSACTION` 广播体逐字节一致。 |
| **签名格式** | `signature = Ed25519.sign(payload)`（base64 编码 64 字节）；`payload` = 序列化后的 `TRANSACTION` 消息体（不含签名字段本身） | 与现有 `TRANSACTION` 广播格式一致（`handleValidatorSet` 已按 `TRANSACTION` 广播回放）。签名字段作为新增可选字段附加在消息末尾（老节点忽略未知字段，向后兼容）。 |
| **Nonce/防重放** | `nonce = max(existing_nonce_for_validator) + 1`（每验证人递增）；`epoch = current_finality_epoch`（与 `finality_checkpoints` 的 `epoch` 对齐） | 利用现有 `validators_synced` 表的 `nonce` 字段（已用于顺序水位）和 `finality_checkpoints` 的 `epoch` 字段（已用于最终性状态）。重放检测可直接查询本地表：同一验证人 + 同一 epoch + 同一 nonce = 重放（丢弃）；同一验证人 + 更高 nonce = 新消息（处理并替换）。 |

### 2.2 消息格式修改（最小化，与现有 `TRANSACTION` 广播对齐）

现有广播格式（`handleValidatorSet` 成功后发送）：
```
TRANSACTION broadcast:
  type: ADD_VALIDATOR / REMOVE_VALIDATOR
  payload_sha256: <hash>
  validator_pubkey: <pubkey>
  ... (其他注册字段)
```

新增签名字段（可选，不破坏现有解析）：
```
TRANSACTION broadcast (v2, 签名可选):
  ... (现有字段逐字保留)
  payload_sha256: <hash>         # 保留（与 F1 指纹转发逐字一致）
  signature: <base64>            # 新增（Ed25519 64 字节 -> base64 ~88 字符）
  nonce: <uint64>                # 新增（每验证人递增，防重放）
```

**解析规则（向后兼容）**：
- **老节点**（未升级）：接收到带 `signature` 字段的消息时，将 `signature` 和 `nonce` 视为未知扩展字段，继续按现有路径处理（解析 `payload_sha256` → `handleValidatorSet` → 幂等注册）。行为与当前修复后完全一致（不增加新行为，也不拒绝消息）。
- **新节点**（已升级）：接收到消息时，先检查 `signature` 是否存在：
  - **存在且有效**（验证通过）：按正常路径处理（解析载荷 → 注册 → 更新 nonce 水位）。
  - **存在但无效**（签名验证失败）：丢弃消息（`log.warn("invalid validator-set signature, dropping")`），不影响现有处理流程（与当前无签名时的行为差异仅在日志）。
  - **不存在**（老节点广播的无签名消息）：继续处理（向后兼容），但在日志中标记 `"unsigned validator-set message received"`（便于后续迁移监控，不影响业务逻辑）。

### 2.3 签名验证路径（集成到现有代码，不新增线程/跨线程面）

验证函数位置（建议放在 `org.nexus.sync.SyncManager` 附近，与现有 `handleValidatorSet` 同一模块）：

```java
// 已实现位置：org.nexus.sync.validator.ValidatorSetSignatureVerifier（纯 JDK 17，本切片交付）
public String sign(byte[] payload, String privateKeyHex)      // 发送侧：32 字节种子（64 字符 hex）→ base64 签名
public SignatureCheckResult check(byte[] payload, String expectedPubKeyHex, String signatureBase64)
    // 接收侧三分语义：VALID / INVALID / NO_SIGNATURE（无签名 → 继续处理，向后兼容）
public boolean isReplay(long nonce, long lastSeenNonce)       // 纯函数：nonce <= lastSeen 即重放（水位由调用方提供）
```

密钥编解码为 RFC 8410 raw 形态（PKCS#8 PrivateKeyInfo / X.509 SPKI DER 包装），与现有 `validator-private-key` 十六进制格式直接兼容（32 字节种子；亦兼容 64 字节 libsodium 形态，取前 32 字节）。

集成点（**【切片 2】已实施**，落点与设计一致；与现有 `handleValidatorSet` 逐行对齐）：
1. **发送侧**（`ValidatorNodeBootstrapper`）：首次广播前对本节点 `add` 载荷签名——
   - `nonce = System.currentTimeMillis()`（单调递增；3 次重播复用同一载荷/签名）；
   - 签名对象为**规范串**（非 JSON 字节）：`ValidatorSetCodec.canonicalSigningBytes`
     = `nexus-validator-set-v2\n{action}\n{address}\n{publicKey}\n{stakeAmount}\n{nonce}`
     （行分隔、null 记空串；避免 JSON 字段顺序/空白的脆弱性）；
   - `signature = Ed25519.sign(privateKeyHex, 规范串)`（私钥取现有 `KeyPair`；
     签名失败降级为无签名广播并告警，不阻断启动）。
2. **消息格式 v2**（`ValidatorSetCodec`）：`"nonce":<long>` 与 `"signature":"<base64>"`
   作为**可选字段追加**在 JSON 末尾；v1 三参编码路径**逐字节不变**（单测锁定），
   老节点忽略未知字段。
3. **接收侧**（`SyncManager.handleValidatorSet`，解析后、注册前）：
   - 决策器 `ValidatorSetMessageVerifier.decide()`（无状态纯函数）：
     - 无签名 → `ACCEPT_UNSIGNED`（兼容模式默认；`enforce` 模式 → `DROP_UNSIGNED`）；
     - 有签名：add 用消息自带 `publicKey` 验签；**remove 用本地注册表登记公钥验签**
       （未登记/错误公钥 → `DROP_BAD_SIGNATURE`，丢弃：不注册、不中继）；
     - 验签通过 + `nonce <= lastSeen` → `DROP_REPLAY`（相等也视为重放）；
   - 重放水位 `lastSeen = max(进程内 watermark, validators_synced.nonce)`；
     仅**验签通过**的消息推进水位（未签名消息不得推进——否则可用无签名消息
     抢占水位、阻塞合法签名消息）；
   - `validators_synced` 增量加列 `nonce BIGINT NOT NULL DEFAULT 0`（additive-only，
     upsert 用 `CASE WHEN nonce < ? THEN ? ELSE nonce END` 保证只增不减）。
4. **开关**：`nexus.consensus.validator-set.signature.enforce`（默认 `false`）。
   **启用前提**：全部发送方已升级，且全表回放消息的签名语义已解决——
   回放消息由注册表数据重建、无原始签名，`enforce` 下会被丢弃、影响晚加入节点
   补历史（回放语义留待后续切片）。

**并发面说明（与 Fifo 修复一致）**：签名验证和消息处理仍在同一工作线程（与现有 `handleValidatorSet` 同步执行，不引入新跨线程可见性面）。签名计算（Ed25519 逐字节操作）在本地完成（无网络依赖），耗时可忽略（单次签名 < 1ms，远小于现有 30s 对账周期）。

---

## 三、测试与验收口径（与现有测试逐条对齐）

### 已交付测试（`ValidatorSetSignatureVerificationTest`，7 例，独立于现有 19 例新增测试，不影响现有绿度）

| 测试用例 | 验证点 |
|----------|--------|
| `rfc8032TestVector1EmptyMessage` | **RFC 8032 §7.1 TEST 1 官方向量逐字节一致**（证明 raw 种子/公钥的 DER 包装路径与标准完全相符） |
| `roundTripSignVerifyIsValid` | 签名/验签往返 → VALID |
| `tamperedPayloadIsInvalid` | 篡改载荷 1 bit → INVALID（防篡改） |
| `wrongPublicKeyIsInvalid` | 错误公钥 → INVALID（防伪造） |
| `malformedSignatureIsInvalid` | 畸形 base64 / 长度错误 → INVALID（不抛异常） |
| `noSignatureIsBackwardCompatible` | 无签名（null/空）→ NO_SIGNATURE（继续处理，向后兼容） |
| `replayDetectionPureFunction` | `nonce <= lastSeen` 重放边界（含相等/更旧/更新） |

### 已交付测试（切片 2：接线；`ValidatorSetMessageVerifierTest` 7 例 + 重放水位持久化 3 例 + 中继接线 3 例）

| 测试用例 | 验证点 |
|----------|--------|
| `legacyV1FormatIsByteIdenticalAndDecodesAsUnsigned` | v1 三参编码**逐字节不变**；解码 `nonce=0/signature=null`；兼容模式受理、enforce 模式丢弃 |
| `signedAddIsAccepted` | 签名 add → `ACCEPT_SIGNED` |
| `tamperedStakeIsDroppedEvenWithValidLookingSignature` | 保留签名篡改质押额 → `DROP_BAD_SIGNATURE`（防篡改） |
| `wrongPublicKeyIsDropped` | 声明他人公钥 → 拒绝（防冒用已登记地址） |
| `malformedSignatureIsDroppedNotThrown` | 畸形签名 → 丢弃且不抛异常 |
| `replayIsDroppedForEqualOrOlderNonceAndAcceptedForNewer` | nonce 相等/更旧 → `DROP_REPLAY`；更新 → 受理 |
| `removeVerificationUsesRegisteredPublicKey` | remove 用注册表公钥验签；未登记/错误公钥 → 丢弃 |
| `ValidatorSetPersistenceNonceTest`（3 例） | nonce 列增量 DDL（H2/PG 兼容模式）；upsert **只增不减**（乱序/legacy 调用不回退）；重启新实例重放后水位保持 |
| `SyncManagerValidatorSetRelayTest` 新增 3 例 | 验签失败不注册不中继；旧 nonce 重放在接线层被丢弃；enforce 模式丢弃未签名消息 |

**验收标准（与现有口径一致，不夸大）**：
- 新增测试在 `:nexus-core:nexus-core:test` 下运行，全模块 `BUILD SUCCESSFUL`（不降低现有绿度）。
- 签名化不引入新的跨线程可见性面（与 Fifo 修复一致，仍同工作线程）。
- 向后兼容性：老节点收到签名消息时忽略未知字段、按既有路径注册（不认识 nonce 列，也不受影响）；新节点收到无签名 v1 消息时行为与修复前一致（解析、注册、不拒绝）。
- **如实边界（重要）**：**默认兼容模式受理未签名消息 ⇒ 防伪造并未真正生效**（攻击者可直接发未签名消息绕过验签）；默认模式的真实增量是"带签名消息的篡改/冒用/重放被拒"。要达到"防伪造已登记地址"需置 `enforce=true`，且其启用前提（全表回放签名语义）尚未满足。签名化不解决"伪造 `seedStake` 注册"的问题（该路径仍无签名，敞口与修复前同等）。

---

## 四、明确边界（与现有文档逐字一致，不夸大）

- **单主机 vs 多机**：本方案在本地构造的**虚拟化边界多机**（三套内核）上设计和测试，不声称在物理多机、独立机房、真实网络分区下逐字复刻（签名计算在本地完成，无网络依赖，此边界不影响签名验证本身，但广播拓扑仍受同一限制：星型 + A 居中）。
- **Windows 语义**：`windowsPath` 场景的签名计算由子类模拟（CI ubuntu 跑签名验证，Windows 本机跳过签名计算测试，但签名计算代码本身与平台无关）。
- **签名化范围**：仅覆盖 `TRANSACTION` 广播层的验证人集 `add/remove` 消息（与现有 F1 修复的挂载点逐字一致），不扩展到 `seedStake` 注册路径、最终性投票消息（`Vote`）、补票请求（`0x5B`）、或任何其他消息类型（除非后续统一扩展）。
- **性能边界**：Ed25519 签名计算在本地完成（无网络），单次签名耗时可忽略（远小于现有 30s 对账周期、3s 出块间隔、5s 轮转超时）；在高频广播场景（每秒多次）下可测量累积耗时，但本方案不引入异步签名线程（仍同工作线程，与 Fifo 修复一致）。
- **信任模型边界修正**：签名化解决的是**广播消息的真实性和不可重放**（防伪造 `TRANSACTION` 载荷），不改变现有信任模型的根本假设（节点仍信任直连对端的广播，不引入第三方信任锚点、不引入区块链外身份验证）。

---

## 五、实施顺序（与现有四缺陷修复顺序逐字一致，不替代）

本方案为协议层后续切片，不影响已交付的四缺陷修复（F3/F2/F1/Fifo）。顺序：

1. **【已交付】** 验证器内核（`ValidatorSetSignatureVerifier`：sign/check/isReplay，纯 JDK 17，无新依赖）+ 7 例测试（含 RFC 8032 官方向量），全模块 `BUILD SUCCESSFUL`。
2. **【已交付】** 接线切片：消息 v2 字段（`nonce`/`signature`，可选追加，v1 编码逐字节不变）→ 发送侧（Bootstrapper）签名、接收侧（`ValidatorSetMessageVerifier` + `SyncManager`）验签与重放护栏、`validators_synced.nonce` 增量列、`enforce` 开关（默认 false）。测试见上表。
3. **【已交付】** 多机回归（三节点换 slice-2 构建，停 C 60s 闭环 + 签名端到端实证）——数据与诚实边界见 §七。
4. **【后续硬化】** ① 全表回放消息的签名语义（当前无签名，是 `enforce` 启用的前置）；② 全部发送方升级后置 `enforce=true`，届时"防伪造已登记地址"才真正生效；③ 如需更强信任模型，再考虑治理层签名/白名单（超出当前切片）。

**与现有修复的关系**：
- F3（轮转 view-change）：不受影响（签名计算在本地完成，不影响本地时钟轮转逻辑）。
- F2（主动追块）：不受影响（签名化覆盖 `TRANSACTION` 广播，不影响 grpc `SyncClient` 追块窗口裁剪逻辑）。
- F1（验证人集中继与全表回放）：签名化直接增强 F1 的挂载点（`TRANSACTION` 广播层的真实性验证），与现有指纹转发（LRU 防环）逐字对齐（签名防伪造 + 指纹防环 + nonce 防重放，三重防护逐层递进）。
- Fifo（守护线程化）：不受影响（签名计算仍在同工作线程，无新增跨线程面，与现有修复逐字一致）。

---

## 六、与现有交付的逐字对齐（验证一致性，不引入矛盾）

- **多机闭环实证数据（`PLAN-027` §六，已交付）**：签名化方案不修改 §六 的数据（T0/T1/T2/T3 高度、B `validators_synced` 3 行、`require blocks 534..542`、`Validator-set broadcast sent`）。签名化交付后，可在同一多机拓扑上重跑闭环（步骤不变，只是广播消息多了签名字段，接收端验证后继续现有注册路径），验证签名化不破坏闭环。
- **CHANGELOG 边界修正（已交付）**：F2 边界句已更新为"多机闭环实证见 PLAN-027 §六"，F3 边界句同理更新；签名化交付后可在同一段落追加"签名化已交付（协议后续）"，不替代现有内容。
- **Roadmap #34/#35（已交付）**：#34 行已标记"已修并实证（PLAN-031）"，#35 行已标记"已修并实证"；签名化交付后可在 #35 行追加"签名化已交付（协议后续，PLAN-001 扩展）"，不修改现有分账。
- **测试绿度**：新增 7 例测试（本切片）在现有测试基线（`FifoBootNotBlockedTest` 4 例、`SyncClientMiningSyncTest` 4 例、`SyncManagerValidatorSetRelayTest` 6 例）上逐字递进，不降低任何现有绿度（签名验证失败时记录警告但不抛异常，保持现有行为不变）。

---

## 七、多机回归（2026-10-24，三节点 slice-2 构建）

> 口径：虚拟化边界多机（Windows 宿主 / WSL2 / Docker 三套内核），非物理多机；三节点统一重置后同时启动。

**签名端到端实证**：
| 证据 | 数据 |
|---|---|
| 三节点广播载荷 | 全部 `Validator-set broadcast sent: 311 bytes`（v1 未签名时代为 186 字节——v2 追加 nonce+签名） |
| A 库 `validators_synced.nonce` | B=1792847235494、C=1792847280878、A=1792847206666 **均 > 0**（A 验签并落库了 B/C 的签名消息） |
| B 库 | A 行 nonce>0（直连签名）；C 行 =0（经无签名全表回放路径——兼容语义生效） |
| C 库 | 自身 nonce>0；A 行 =0（同上，回放路径） |
| 误拒 | **0 条 bad-signature 丢弃**；重复投递被重放护栏幂等丢弃：A=6 / B=1 / C=0 条（P2P 重连重投的同一载荷，丢弃无副作用） |

**停 C 60s 闭环（22:19–22:21）**：
- T0 22:19:18 A=1712 / B=706 / C=1712；
- 停 C 60s → T1 22:20:25 **A=1722（+10）/ B=717（+11）**——链路未断；
- 恢复 C → T2 22:21:27 **C=1745 ≈ A=1743（ALIGNED，60s 内追平）**。

**如实边界（本环境）**：
- B 长期落后（706 vs 1712，差距在扩大）：为本环境**既有 grpc 追块速率边界**（F2 修复覆盖 rest 形态；grpc 大盘缺口追块速率 ~10 块/分钟 < 出块 ~26 块/分钟），**非签名切片引入**——签名化前后 B 的广播/验签/注册路径均正常（nonce>0）。
- A→B 方向拨号 `CANCELLED`（WSL↔Windows 网络环境问题）；B 经自身外拨完成状态交换，功能面不受签名切片影响。
- 本回归不声称覆盖物理多机、网络分区、断电；`enforce` 仍为默认关（未启用）。
