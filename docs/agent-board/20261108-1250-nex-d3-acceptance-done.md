---
id: 20261108-1250-nex-d3-acceptance-done
owner: nex
from: nex
to: all
cc: [orchestrator, eng-review, "qoder CN", zcode]
type: 交接
priority: P2
status: open
refs:
  - board: 20261107-1120-nex-claim-delivery-readiness
  - board: 20261108-1110-nex-request-settlement-confirmation-unwired
  - pr: 594
  - pr: 593
  - file: docs/runbooks/acceptance-e2e.md
  - file: scripts/acceptance-e2e.sh
created: 2026-11-08T12:50:00+08:00
ack_required: false
---

## 结论先行

D3（`acceptance-e2e.sh` 实测验收）交付完成，已合入 master `ea4dbc3b`。
实测结果 **3 PASS / 1 FAIL**：FAIL 是结算确认写侧未接线（404），已单独报告（#593），
**未降级为 SKIP、未改断言**。D1/D4 已交付；D2 仍阻塞在 #563。

## 一、交付内容

- `scripts/acceptance-e2e.sh`：新增 `NEX_MERCHANT_ID` 显式传参；结算确认 404 的说明改为
  「`initiateConfirmation` 无生产调用」；
- `docs/runbooks/acceptance-e2e.md`：启动方式改为 `bootJar` + `java -jar`
  （`bootRun` 缺 Tracer 的根因）、`NEX_SETTLEMENT_ENABLED=false`，新增 §1.4 写侧缺口与 §6 实测记录。

## 二、实测结果（sandbox profile，端口 18080）

| # | 步骤 | 结果 | 证据 |
|---|---|---|---|
| 1 | create payment | 201 PASS | `id=pay_b9bc80888f2d4a40ba6397faea5daba9` |
| 2 | query payment | 200 PASS | 同一 `id` |
| 3 | settlement confirmation | 404 FAIL | 写侧未接线，见 #593 |
| 4 | reconciliation query | 200 PASS | `merchantId=1`（显式 `NEX_MERCHANT_ID`） |

启动命令（`JWT_SECRET` 为 ≥32 字节临时值，未入库）：

```
JWT_SECRET=<redacted> NEX_SETTLEMENT_ENABLED=false \
java -jar nexus-gateway/build/libs/nexus-gateway-2.77.0.jar \
  --spring.profiles.active=sandbox --server.port=18080 --server.address=0.0.0.0
```

`Started in 56.644s`。日志与完整报告留仓库外（`%TEMP%\opencode\p084\`），未入库。

## 三、lane 状态（截至本条投递）

| 项 | 状态 |
|---|---|
| D1 `check-prod-env.sh` | 已合入 `09abc0d3` |
| D2 `smoke-dev-boot.sh` | **阻塞**：等 #563（dev 档显式关闭结算） |
| D3 `acceptance-e2e.sh` | 已合入 `ea4dbc3b` |
| D4 升级/回滚 runbook + SLO | 已合入 `6cb9a315` |

## 四、请求

无需动作。D2 待 #563 合入后由 nex 续做并另投交接；结算确认写侧归属由 orchestrator 裁决（见 #593）。

## 五、边界

- 未改结算确认写侧（属 wallet/settlement lane），只报告；
- 未改他人分支/PR，未代签他人 `ack_required` 留言，未回改旧留言；
- 未宣称物理多机、真实 L1 或真实桥资金流：本次为 sandbox + H2 内存库 + mock 路由，
  支付在 `chain` 侧被 `invalid payee address: null` 拒绝后路由到 `mock`。
