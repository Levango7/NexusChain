# ADR-040：BATCH_TRANSFER 服务、SDK 与 Explorer 接线（A4）

- **状态**：Accepted，A4 实施中
- **日期**：2026-11-28
- **Owner**：ZCode
- **范围**：仅已完成 A2a/A3 的 `BATCH_TRANSFER`；其他支付扩展仍不开放

## 1. 服务与受理

1. `BatchTransferService` 使用真实 Ed25519 私钥签名或验证调用方提供的 `traninfo`，再进入 `CommandService.verifyTransfer`，不再返回占位成功或模拟状态。
2. `tx.amount`、规范 `items`、发送方、nonce、空 `to` 字段必须与签名体一致；激活高度由 `best + 1` 决定。未激活继续返回 5010。
3. `CommandServiceImpl` 成功入池后显式返回 `2000/SUCCESS`，避免交易已受理但响应停留于默认 code=0。
4. 新增 `/batch/transfer/v2`，允许 SDK 只提交本地签名的 `traninfo`，无需通过 URL 或请求体传输私钥。旧 list+query 路由保留。

## 2. 真实状态与 SDK

1. 状态查询通过 `NexusChainBlockChain.getTransaction(txHash)` 读取已确认交易，返回 `CONFIRMED`、确认高度、总额、实际手续费、nonce 和规范收款项；交易自身高度必须处于 V3 激活范围。
2. Java SDK 的 raw、签名和高层调用统一使用 `amount == payload 总额` 与规范 `items`，保留旧 `to` 字段到 `address` 的兼容映射。
3. SDK 高层写路径只发送本地签名结果，不发送私钥。节点 URL 仅允许公开 HTTP(S) 地址，拒绝用户信息、query/fragment、回环、私网、链路本地与保留地址；HTTP 重定向关闭。
4. 金额按最小单位字符串处理，禁止经 JavaScript/Java 浮点或 JavaScript Number 往返。

## 3. Explorer

1. `toRpcTransaction` 只为 BATCH 增加可选 `type/typeName/fee/nonce`，旧交易响应形状不变。
2. 通用交易详情页展示这些增量字段及规范收款项；畸形或空 payload 不影响主详情，不执行任何 payload 内容。
3. 前端在 `JSON.parse` 前给整数金额加引号，保留超过 JavaScript 安全整数范围的精确值。

## 4. 验证与边界

- 服务签名必须通过真实 `TransactionCheck`，并通过真实 `CommandServiceImpl` 进入受理池。
- SDK 签名结果必须通过共识格式校验；读路径不得返回模拟状态。
- 前端须通过单测、TypeScript 严格检查和生产构建。
- 本 ADR 不提供生产激活、真实 L1、HA、真实资金、互联网 DDoS、生产容量、长期 SLA 或法务结论。
