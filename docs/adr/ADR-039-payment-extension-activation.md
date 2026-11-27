# ADR-039：BATCH_TRANSFER 的按高度激活与共识接线（A3）

- **状态**：Accepted，A3 实施中
- **日期**：2026-11-27
- **Owner**：ZCode
- **范围**：仅 `BATCH_TRANSFER` 的格式门禁、交易池准入、打包与账户/手续费守恒；不包含其他支付扩展、服务端 SDK 或生产激活

## 1. 决策

1. `nexus.payment.extension.activation-height` 继续默认 `Long.MAX_VALUE`。代码合入不等于激活，部署方必须显式安排硬分叉高度。
2. 激活后也只放行 `BATCH_TRANSFER`。其余 `0x10–0x1a` 类型在格式校验、交易池、打包和状态重放中继续 fail closed。
3. `tx.amount` 必须等于批量 payload 总额，与 ADR-038 和 `PaymentExecutionKernel` 一致。旧的 `amount == 0` 预检作废，不再保留双套金额契约。
4. RPC 格式校验和交易池准入使用当前最佳高度的下一高度；`AccountRule` 必须使用被验证区块自身高度。当前最佳高度不得追溯改变历史块的合法性。
5. `AccountRule` 与 `PackageMiner` 都调用 ADR-038 的同一个原子内核。执行成功后才允许更新账户副本；余额、nonce、收款和实际手续费均只记一次。
6. `CoinbaseRule` 在 BATCH 已激活的区块高度接受其真实手续费，并使用精确加法校验手续费守恒。
7. 预激活区块继续使用 legacy/V2 规则；激活区块使用 V3。回滚到预激活高度必须恢复旧根，重新激活重放必须逐字节重现原 V3 根。
8. RPC 入口同样校验 BATCH 的 recipients/total 上限、正数、地址和 `amount == payload 总额`，禁止合法签名但不可执行的交易先入池。地址版本与 `KeystoreAction.verifyAddress` 统一为解码版本 1，避免合法交易在验签前被误拒。

## 2. 不变量

- 预激活 BATCH 不得入池、打包或进入状态重放。
- 激活后的任何其他支付扩展类型不得借 BATCH 的路径放行。
- 任一执行失败不得留下部分扣款、部分收款或支付状态条目。
- 混合同一发送方的旧交易与 BATCH 时，两条路径的余额与手续费必须守恒。
- 测试不得只验证开关文本，必须覆盖精确高度、混跑、回滚、重放和重复记账边界。

## 3. 非目标与开放边界

- 其他支付扩展需要各自独立的状态机、payload、余额守恒、重放与激活设计。
- A4 的服务签名、真实状态读取、SDK 和 Explorer 展示不属于本 ADR。
- 本 ADR 不提供生产激活命令，也不构成生产、真实 L1、HA、真实资金或 SLA 验收。
