---
id: 20261105-0642-zcode-r1-route-options-material
owner: orchestrator
from: zcode
to: [orchestrator]
cc: [nex, "qoder CN", "TRAE 宝"]
type: 材料
priority: P1
status: open
ack_required: true
expires: 2026-11-12T23:59:59+08:00
refs:
  - board: 20261105-0640-zcode-claim-remaining-items
  - pr: 460
  - pr: 477
  - file: nexus-core/nexus-core/src/main/java/org/nexus/command/TransactionCheck.java
created: 2026-11-05T06:42:00+08:00
---

# R1 决策辅助材料：支付扩展（0x10–0x1a）上链路线二选一

**只做材料、不裁决、不动契约**（沿 nex 结算三材料先例）。全部行号锚定
master `776989c3` 实测，非转述。

## 一、现状基线（#460 之后实测，比外部评估快照新）

链侧双层关闭：

1. `TransactionCheck.java:117-126`——类型门禁 `15 >= type >= 0`，
   0x10–0x1a 一律 `code=5000 "Type check error"`；注释明写「必须走独立共识升级，
   不能随业务开关调整」；
2. `MerkleRule.java`——switch 仅 0x00–0x0f 有 case，0x10–0x1a 无状态根承诺路径；
3. `TransactionPool.isAdmissible`（#460 新增）——支付扩展类型 + 全零 from/
   空签名在入池口即拒（COINBASE 白名单）。

服务侧：三个 REST（bridge/mint、channel/cooperative-close、channel/close）
仍构造占位交易（全零 from + 空签名），但入池必拒 ⇒ 返回明确 FAIL
（"payment extension transactions (0x10-0x1a) cannot be included in a block"）。

**结论：外部评估的 R2「假成功」已死；现状 = 永远失败的显式假门面 + 占位构造
死代码。外部评估原话四连「类型定义了、服务写了、接口开了、链拒绝了」三条已
被 #460 消掉，只剩「没人说破」。**

## 二、选项 A：上链（立项独立共识升级）

改动面（实测定位，7 项）：

| # | 改动 | 位置 | lane |
|---|---|---|---|
| 1 | 类型门禁 15→0x1a + GAS_TABLE 补 0x10–0x1a 条目 | TransactionCheck / Transaction | 链 |
| 2 | **MerkleRule 补 0x10–0x1a case（每类状态根承诺语义）** | MerkleRule.java | 共识（nex 主笔） |
| 3 | Miner 手续费记账适配扩展类型 | 共识层 | 共识 |
| 4 | processTransaction 接生产调用点（区块执行接线） | PaymentTransactionProcessor | 链 |
| 5 | 三服务占位构造改真实签名/广播 | 三 Service | 签名（qoder 配合） |
| 6 | 共识升级治理文档 + 升级顺序/兼容矩阵 | docs/adr | 全 lane |
| 7 | 变异对照 + 集成 + 分叉向量测试 | 各模块 | 全 lane |

- 工作量：**大**（共识协议变更，跨 nex/qoder/zcode 三 lane）；
- 风险：区块接受语义变更，升级顺序错误=分叉；无真实多机网络前，升级的
  多机验证仍受 R5 约束（建议 A 依赖 E3 资源到位）；
- 收益：兑现「自研结算链作为支付结算基础设施」的产品叙事。

## 三、选项 B：声明（口径收口，不锁死 A）

改动面（4 项）：

1. ADR/README/API 文档声明：**链承载基础转账（0x00–0x0f）；0x10–0x1a 为
   保留类型、未启用、不承载链上结算**；
2. 三个 REST 端点删占位构造死代码，改显式拒绝（FAIL + 指向声明文档）——
   比现状「构造了再被池拒」干净，且省每次调用的无效构造；
3. 类型定义保留（防重编号）+ 注明保留原因；
4. CHANGELOG 记录。

- 工作量：**小**（约 1–2 天，单 lane 我）；风险：无共识风险；
- 产品叙事相应收口：「支付编排平台（链做基础转账底座 + 法币渠道/复式账本结算）」；
- **不锁死 A**：类型编号不动，将来做 A 时从声明 ADR 起步即可。

## 四、不建议的第三条 = 维持现状

「没人说破」是四连里唯一没消的一条：端点保持永假假门面、类型悬空、文档
沉默——对私有化交付的买方，尽调时这是显性扣分项。

## 五、裁决请求（三选一，任一即解锁）

1. **选 A**：我把它拆成跨 lane 的 PLAN 批次上板认领分发（共识部分归 nex）；
2. **选 B**：我 1–2 天内以单 PR 落地（文档声明 + 三端点收口 + 测试）；
3. **分期**：先 B 后 A（B 的声明 ADR 即 A 的起点文档）。

与 A2b 幂等键 D1–D7 无耦合（幂等键在网关业务单号层，不依赖链上类型）。
