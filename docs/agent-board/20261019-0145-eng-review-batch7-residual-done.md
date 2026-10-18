---
id: 20261019-0145-eng-review-batch7-residual-done
owner: eng-review
from: eng-review
to: all
cc: []
type: 通报
priority: P2
status: open
refs:
  - pr: 140
  - pr: 141
  - board: 20261019-0015-eng-review-batch47-done
  - file: docs/audit/2026-10-18-eng-review-and-remediation-plan.md
created: 2026-10-19T01:45:00+08:00
---

## 主题

批次⑦残项复核闭环——0015 留言所记「SDK 各语言测试 CI 真实覆盖、perf/demo 资产审计记账批次⑧候选」三项**已补做完成**（记入 plan §7 与本文），不再留待批次⑧。

## 事实（可复现）

- **SDK 四语言真实覆盖核验**（原 §4 L173 复核项）：Go 13 用例（`go test ./nexus/`）/ Python 15（`python3 tests/test_nexus_sdk.py`）/ TS 8（`npm run test:ts`）——三 step 在最终 HEAD run `39140973458` 的 Build & Test job 全 **success**，且用例数与 ci.yml 注释（Go 13 / Python 15 / TS 8）逐一对齐；Java SDK 20 个测试文件走根构建 check + codecov（files 列表含 nexus-sdk/java）。
- **explorer 构建链 Node**：explorer job / build-and-test / Hardhat 三处统一钉 26（三处一致，本批补的是 build-and-test 的 `Setup Node 26`）。
- **perf/demo 定位**：perf/k6 为真实资产（4 场景 + 容量基线 + HMAC 认证工具），本 PR k6 冒烟为**实跑**——执行态日志 `##[notice]变更涉及被测组件 → 执行 k6 冒烟实跑` + bridge 探针 warning + 服务栈启动 245s（run `39140973534`）；不相关 PR 按设计 skip 记 success（防悬空 required check，逻辑与注释在 performance-test.yml 内）；capacity 基线首跑被 staging secrets 阻塞（`docs/roadmap-next.md` C2 在案）；demo/ 为独立本地演示包（express mock + 七步流程脚本，ARCHITECTURE.md 为其使用示例），无 CI 引用、非集成证据——无需变更。
- **记录落点**：`docs/audit/2026-10-18-eng-review-and-remediation-plan.md` §7（批次⑦残项复核）+ §4 L173 标记闭环 + §0 提交口径；随 PR #140 文档补记提交。

## 边界

无代码改动（纯核验/文档补记）；本条经专用 PR #141 投递（§4）；批次⑧定义（报告 §6）不变，其在案候选（OWASP required 去留 / origin-master 历史整理）仍待人类拍板。
