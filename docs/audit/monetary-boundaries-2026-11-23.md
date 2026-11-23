# 金额与数值边界修复（2026-11-23）

## 结论

PostgreSQL/MySQL 的 `DECIMAL` 都能精确存数，问题取决于应用类型与运算。当前 gateway
已有 `BigDecimal + DECIMAL(36,x)` 的正确主路径，但 core、Escrow 和前端仍存在具体溢出/精度风险。

## 实修

1. **Escrow JSON Number**：`toBigDecimal` 不再先走 `Number.doubleValue()`，改由字符串精确构造，
  `9007199254740993` 不再变成 `9007199254740992`。
2. **StableCoinPosition**：mint/抵押率/清算价/赎回/补抵押/清算/抵押价值全部改精确整数或
  BigDecimal；负值比例归零、超 int 比例封顶；溢出明确失败，不再回绕。
3. **PaymentChannel/ChannelManager**：余额守恒、付款接收方余额、nonce 使用
  `Math.addExact/subtractExact`，溢出时 fail-closed。
4. **前端金额显示**：字符串不再经过 `Number` 二次舍入，`amount` 类型同步为
  `number | string`；普通 number 仍按 locale 格式化。

## 修前直接证据

- core 7 个直接生产方法边界测试全部失败；Escrow 大整数测试失败。
- 稳定币 `collateral * 100` 溢出会把充足抵押率误算成负值；`getCollateralValue`、补抵押、
  清算和通道接收方余额都能回绕。
- 资金页把 `9007199254740993` 显示为 `9,007,199,254,740,992`。

## 修后证据

- core 边界 7/7 通过；Escrow 2/2 通过；资金页精度回归通过。
- frontend **22 files / 223 tests**、format/lint/build/verify:build/verify:contrast 全绿。
- core `test + spotbugsMain` 全量 `BUILD SUCCESSFUL`（4m4s）；
- gateway `test + integrationTest + spotbugsMain` 全量 `BUILD SUCCESSFUL`（6m17s）。

## 边界

这些测试证明实现级数值边界，不证明真实链结算、主网资金、生产数据库或真实 L1 已验收。
小额 testnet 交易用于链路验证，大数与溢出仍必须靠上述合成边界测试覆盖。
