# Sandbox 语义与生产红线（roadmap #12/#13/#14）

- **状态**：Active
- **日期**：2026-08-14
- **目的**：明确 gateway 中所有"模拟成功"路径的触发条件、可识别标记与生产禁用红线，
  防止开发/运维误把沙箱结果当真实资金结果。

---

## 总则

**沙箱（sandbox）结果的本质**：返回"看起来成功"的假哈希/假 ID，仅用于本地开发与
联调（sandbox profile / 无真实凭据时）。**生产环境必须确保所有 sandbox 路径关闭**，
否则会出现"账上有、链上无"或"账上有、外部 PSP 无"的资金错账。

每条 sandbox 路径应满足：
1. **显式触发**：仅由配置/profile 触发，非隐式兜底
2. **可识别**：结果带明确前缀或 simulated 标记，上游可区分
3. **可禁用**：生产配置一条命令关闭

---

## #12 DefaultOnChainExecutionChannel（链上执行通道）

| 项 | 内容 |
|---|---|
| 位置 | `nexus-gateway/.../execution/DefaultOnChainExecutionChannel.java` |
| 触发 | `nexus.chain.skip-confirmation=true`（sandbox profile 默认 true） |
| 行为 | 不调用真实签名服务/链上节点，返回 `SIMULATED-` 前缀假哈希 |
| 标记 | `SIMULATED_PREFIX = "SIMULATED-"`；`isSandboxMode()` |
| **生产红线** | `skip-confirmation` 必须 `false`；且生产无平台公钥时**fail-closed**（报错而非伪造） |

## #13 DefaultRefundApprovalService（退款执行）

| 项 | 内容 |
|---|---|
| 位置 | `nexus-gateway/.../refund/DefaultRefundApprovalService.java` |
| 触发 | 复用 `OnChainExecutionChannel` 的 sandbox 语义（`SIMULATED-`） |
| 行为 | sandbox 下退款返回 `SIMULATED-` 假哈希并标记 EXECUTED |
| **生产红线** | 依赖 #12 的 `skip-confirmation=false`；生产未上链退款须走 FAILED + 人工介入 |

## #14 Stripe/Adyen Connector（无真实 key 时）

| 项 | 内容 |
|---|---|
| 位置 | `StripeConnector` / `AdyenConnector`（`orchestration/connectors/`） |
| 触发 | `apiKey` 未配置（blank）时 |
| 行为 | `createPayment` 返回 `pi_dryrun_` / `adyen_dryrun_` 前缀假 ID，`localState` 置 SUCCEEDED |
| 标记 | `pi_dryrun_`（Stripe）、`adyen_dryrun_`（Adyen）；日志 `[Stripe DRY-RUN]` |
| **生产红线** | 必须配置真实 API key；无 key 时 gateway 应 fail-fast（连接器注册时校验） |
| 注意 | 无 key 时 `queryPayment` 返回 FAILED（与 create 的 dry-run 不一致）——已知行为，文档化 |

---

## 生产环境 checklist（部署前逐项确认）

- [ ] `nexus.chain.skip-confirmation=false`（#12/#13）
- [ ] `stripe.api-key` / `adyen.api-key` 已配置（#14）
- [ ] 无 sandbox profile 残留（`--spring.profiles.active` 确认）
- [ ] 退款走真实链上（无 `SIMULATED-` 哈希）

## 相关

- fail-closed 修复历史：wallet-service `SIMULATED-` 假哈希已移除（资金路径零伪造）
- 本文件对应 roadmap-unresolved #12/#13/#14
