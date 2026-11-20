---
id: 20261120-1430-zcode-integration-report-remediation
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: docs/audit/integration-report-review-2026-11-20.md
  - file: nexus-core/nexus-core/src/main/java/org/nexus/controller/JsonRpcController.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/core/StateReplayManager.java
  - file: nexus-explorer/backend/src/rpc.ts
created: 2026-11-20T14:30:00+08:00
---

# 通告（P1）：第二份集成报告复核完成——真缺陷实修，安全阈值不放宽

## 已坐实并修复

1. JSON-RPC 成功 `ObjectNode` 误入 `error`：按真实错误信封字段判别，协议回归 3/3。
2. BFF 不带 core `token`：新增 `NEXUS_RPC_TOKEN`，真实 HTTP 转发 8/8；dev 显式可信本地编排，prod 三项 RPC/JWT secret 强制，env 一致性 22/22。
3. CORS 预检真 401：API Key/HMAC/限流对 OPTIONS 放行；完整 dev 预检 200、真实 POST 仍 401，3/3。
4. 任意伪造 `SIMULATED-*` 不再仅凭前缀确认成功；sandbox 本身保留并继续显式 `isSimulated=true`。
5. StateReplay 两类状态同事务，Incubator 写失败不再吞异常；真实 H2 证明回滚/提交/幂等，顺带修 `ON CONFLICT` 的 H2/PG 可移植写法。
6. `NoopTracerConfig` 覆盖 sandbox，无 mock 的完整 sandbox 启动验证通过。
7. 前端：跨链刷新 loading、Settings 定时器清理、资金三表 sticky、首页活性按链头时间、连接卡片居中。

## 报告纠偏

- `SIMULATED-*` 是显式 sandbox，不应误改成真实链上执行；本轮只修「任意哈希判成功」。
- StateDB 单活跃验证人本来就返回 1；新增测试锁定 1→1、3→2，**不得为消除多数派不足停顿而降低 2/3 门槛**。
- 编排页 401 引导与全站 PageHeader sticky 已存在；真正缺的是资金三张表的表头 sticky。

## 验证

- core 全测试 + gateway test/integration/SpotBugs：首轮全套 SUCCESS；gateway 最终全套再次 SUCCESS。
- BFF 8/8，typecheck/lint/format 全绿。
- frontend 220/220 + 完整 check；真实浏览器 5/5（含资金三表实际滚动 sticky 几何）。

## 不虚标

完整 compose 跨进程 JWT 握手、真实 PG 故障注入、real-L1、staging/prod、云集群、full/capacity、长期 SLA、法务许可未闭合；详见复核报告边界节。本通告不是生产就绪声明。
