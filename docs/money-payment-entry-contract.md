# money 支付入口契约（A1，链下固化）

> 验证：`nexus-core` 测试 `org.nexus.service.PaymentExtensionEntryContractTest`。
> 本契约只固化现状，**不启用**支付扩展写交易、不改链上门禁。

## 1. 写端点：统一 5010

合法入参下，下列端点统一返回 `5010 PAYMENT_EXTENSION_NOT_ENABLED`
（固定消息 `Payment extension (0x10-0x1a) is reserved but not enabled`），
不构造交易、不修改链上/通道/仓位状态：

| 服务 | 端点 |
|---|---|
| `StableCoinService` | `mint`、`redeem`、`getCollateralRatio` |
| `BridgeService` | `lock`、`mint`、`burn`、`getStatus` |
| `PaymentChannelService` | `openChannel`、`closeChannel`、`getChannelState` |
| `BatchTransferService` | `batchTransfer`、`getBatchStatus` |
| `ChannelSettlementService` | `cooperativeClose`、`unilateralClose`、`settleChannel`、`forceExpire` |

入参校验先于 5010：非法入参仍返 5000（如空地址、金额 <= 0、验签数不足）。
签名数不足是 fail-closed（`validateMintRequest`），不得当作“未启用”返回。

## 2. 读端点：继续可用

| 端点 | 数据 |
|---|---|
| `StableCoinService.getPrice()` | `symbol` / `price` / `source` / `timestamp`（默认 NEX / 1.00 / PEG） |
| `BridgeService.getLimit()` | `singleTxLimit=1e9` / `dailyLimit=1e10` / `timelockDuration=3600` / `minValidators=3` |
| `PaymentChannelService.listChannelsByAddress()` | 通道列表骨架（当前空列表） |
| `ChannelSettlementService.getSettlementStatus()` | 结算/通道状态（需要注入 `ChannelManager`） |

注意：`BridgeService.getLimit()` 的 `dailyUsed=0`、`dailyRemaining=dailyLimit` 是骨架值，
不能作为真实配额/流程可用性依据。

## 3. 边界

- `TransactionCheck` `type<=15`：0x10–0x1a 不可上链，5010 与门禁口径一致。
- 真正启用（A2 全量 / A3 稳定币试点）需要链规则决策，不在本契约范围。
