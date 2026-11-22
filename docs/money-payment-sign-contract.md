# money 支付签发通道契约（MCP 对接，链下）

> 状态：链下契约，不改链上门禁、不启用 0x10–0x1a 写交易。
> 验证：`nexus-core` 测试 `org.nexus.service.BridgeMintMessageHashContractTest`。

## 1. messageHash 编码（跨语言必须同构）

Java 权威实现：`org.nexus.service.BridgeService.computeMintMessageHash`。

```
keccak256(
  "NEXUS-BRIDGE-MINT-v1" 0x00
  bridgeTxId             0x00
  sourceChain            0x00     # null 与 "" 等价
  recipient              0x00
  uint64_be(amount) uint64_be(timelockExpiry)
)
```

固定向量（独立 Python pycryptodome Keccak-256 算出，测试中硬编码比对）：

| bridgeTxId | sourceChain | recipient | amount | expiry | hash |
|---|---|---|---|---|---|
| br-1 | ETH | 0xabc | 1000 | 1700000000 | `20cefb336a17d35342a312f0f047ed7cadc875f31ede9965d775cad2e9fb733b` |
| br-2 | (空) | recipient | 0 | 0 | `dc525bdc1446df7794166a382015de3c0b90bed46bd245e82e6be9f22b1b979f` |
| bridge-xyz | BSC | 0xDEADBEEF | 123456789 | 1999999999 | `f442372a63a712e8b687a959c6cf09f9e0cbbe3478afef90faf7cb8149053684` |

注意：分隔符是 `\0`，不是长度前缀；金额与时锁为 big-endian uint64；recipient 区分大小写（UTF-8 原样入哈希）。

## 2. 阈值（不写死，动态读）

- `BridgeService.getLimit()` 返回 `minValidators`（默认 3）、`singleTxLimit`、`dailyLimit`、`timelockDuration`。
- `dailyUsed` / `dailyRemaining` 当前为骨架值（0 / dailyLimit），**不得**用作流程可用性判定。
- `validateMintRequest` 在验证人数不足时先返 5000 fail-closed，早于 5010 未启用响应。

## 3. 上报幂等键

```
key = type + "|" + lowercase(messageHash) + "|" + decimal(timestamp)
```

- type 非空；messageHash 必须 64 位 hex（大小写归一为小写）；timestamp 为正整数。
- 消费端按 messageHash 去重，与链上 `BridgeMintReplayGuard`（messageHash 幂等）及
  `BridgeLifecycleReplayGuard`（语义锁键）口径一致。
- 上线前同构测试：同一 (type, hash, timestamp) 在 MCP 与 Java 侧产生相同 key。

## 4. 边界

- 5010 `PAYMENT_EXTENSION_NOT_ENABLED` 表示“保留未启用”，不是实现故障。
- `TransactionCheck` `type<=15`：0x10–0x1a 当前不可上链；本契约仅覆盖链下签发信上报+校验。
- 签发依据（mindex `sign_error.json` 7 状态计数）、sign4susps 默认口径、真实配额属外部数据，
  需人云跑值 + Zcode confirm，不得由本通道伪造。
