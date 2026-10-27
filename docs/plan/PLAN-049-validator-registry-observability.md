# 方案 PLAN-049：验证人注册表可观测性（承接 PLAN-047 风险 Slice 1，只观测不剔除）

- **状态**：已交付（2026-10-27）；单测 5 例（catch-up 快照类新增 2）
- **前置**：PLAN-047（Run 1 产品风险：重启换钥 ⇒ 注册表混入第 4 验证人 ⇒ 2/3
  门限不可达 ⇒ 最终性冻结而链继续出块）、PLAN-033 切片 B（`/consensus/catch-up`
  只读端点）、PLAN-001（验证人集消息信任模型：未签名 ⇒ 伪造 remove 与修复前同等）
- **范围**：`SyncManager.catchUpSnapshot()` 追加 `validators` 只读摘要
  （enabled/total/active/inactive/capacity/地址列表）；**零共识逻辑改动**
- **不包含**：自动剔除僵死验证人、注册表 GC、门限计算变更（阻碍见 §四）

---

## 一、问题（PLAN-047 风险的"最后一公里"）

PLAN-047 Run 1 暴露：节点重启换密钥 ⇒ 注册表混入第 4 个**不再投票**的验证人
⇒ 总质押被稀释 ⇒ A+D=50% < 2/3 门限 ⇒ **全网最终性冻结，而链继续出块**
（最危险的静默退化）。PLAN-048 已让它"可被发现"（`FinalityStalled`：
progress 冻结 ⇒ critical + runbook）。

但排障时运维缺一份直接证据：**注册表现在有谁、谁是 ACTIVE/INACTIVE、离容量上限多远**。
此前只有 `validators_synced` 表（DB 直连）和 `/consensus/validator-set`
（回放载荷，不含计数/状态），要回答"是不是混进了僵死验证人"必须翻库。

## 二、方案（零共识逻辑改动）

`GET /consensus/catch-up` 的 `catchUpSnapshot()` 追加一个 `validators` 子对象：

| 字段 | 含义 |
|---|---|
| `enabled` | 注册表是否装配（false ⇒ 其余字段不出现，不用 0 冒充） |
| `total` / `active` / `inactive` | 全量 / ACTIVE / 非 ACTIVE 计数 |
| `capacity` | `ValidatorRegistry.getMaxValidators()`（默认 100） |
| `activeAddresses` / `inactiveAddresses` | 排序后的地址列表（供与 `/consensus/validator-set` 对账） |

**为什么不放新端点**：`/consensus/catch-up` 已是只读运维端点且白名单齐备，
追块与注册表健康同属"节点同步面"观测；新增端点要再过一次白名单/文档周期。
**为什么不动数据**：见 §四。

## 三、测试（5 例，其中 2 例新增）

`SyncManagerCatchUpSnapshotTest`：
- `withoutRegistryReportsDisabledNotZero`：未装配 ⇒ `enabled=false` 且**不出现**
  计数字段（不用 0 冒充）；
- `registrySnapshotCountsActiveAndInactive`：注册 2 人、unregister 1 人 ⇒
  total=2/active=1/inactive=1/capacity=100 + 地址列表正确（排序稳定）。
- 既有 3 例（gap/窗口/暂停）不回归；`SyncManagerValidatorSetRelayTest` 13 例不回归。

## 四、阻碍与为什么不继续做（自动剔除）

**自动 GC 僵死验证人是治理/安全决策，不是本切片能拍的**，具体阻碍：

1. **判据缺失**：多久没投票/没广播算"僵死"？需要 SLA 输入（分区容忍窗口）。
   凭工程直觉设一个值，可能把"临时分区"的健康验证人踢出局——直接伤害 liveness；
2. **信任模型不允许**：验证人集消息**未签名**（PLAN-001：enforce 默认 false，
   伪造 add/remove 与修复前同等）。在签名化之前做自动 remove 路径，等于给攻击者
   一个"伪造成员资格"的面——安全性倒退；
3. **权限未定**：谁有权移除验证人（治理多签？运维单点？）没有决议。

**本切片定位**：把"可被发现"补到最后一环（计数+名单），把处置留给 runbook
（见 §五）。自动剔除的前置（签名化 enforce + SLA + 权限决议）明确登记为后续。

## 五、runbook 处置口径（已写入 alerts.md）

发现 `active` 数 > 预期 / `inactive` 非空 / `FinalityStalled` 触发时：
1. `curl /consensus/catch-up` 看 `validators.activeAddresses` 与
   `curl /consensus/validator-set` 的回放地址对账——多出来的就是候选僵死项；
2. 对每个候选：确认其进程/密钥是否真的下线（换钥重启会在注册表留下新旧两个地址）；
3. 处置走**治理路径**（移除验证人是成员资格变更，需有权实体签名/批准），
   不用手工改库——`validators_synced` 是共识安全状态，直改等于绕过协议。

## 六、边界（勿夸大）

- 只读摘要、进程内视角：重启后从 `validators_synced` 重放恢复，与持久化一致；
- 不含"最后活跃时间"（那需要每个验证人的 last-seen 水位，属另一切片——
  若要做，应与签名化 enforce 同批评估）；
- 未做实机联调（`curl` 验证留待下次测试床窗口，与 PLAN-044 的遗留项一起）。
