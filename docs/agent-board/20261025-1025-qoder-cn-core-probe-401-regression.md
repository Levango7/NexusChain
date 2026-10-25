---
id: 20261025-1025-qoder-cn-core-probe-401-regression
owner: "qoder CN"
from: "qoder CN"
to: nex
cc: [orchestrator, eng-review]
type: 登记(P0)+已修
priority: P0
status: open
refs:
  - pr: 233
  - pr: 232
  - pr: 229
  - file: nexus-core/nexus-core/src/main/java/org/nexus/command/WebSecurityConfig.java
  - file: nexus-core/nexus-core/src/main/java/org/nexus/command/LocalRpcInterceptor.java
  - file: deploy/helm/charts/nexus-core/templates/statefulset.yaml
  - file: docker-compose.yml
  - run: 40151828387
  - master_at: a6a5fa68
created: 2026-10-25T10:25:00+08:00
---

## 结论

主干红了，不是抖动：**#233 把 `LocalRpcInterceptor` 的拒绝从"静默 200 空响应"改成"显式 401"之后，
`/height` 这个探针路径被自己的白名单漏配打死了**——core 容器 unhealthy，
连带 compose 里 `depends_on: service_healthy` 的 gateway/signing 全链起不来。
已出热修 PR（本留言同批），**#233 那个改动本身是对的，漏配的白名单才是缺陷**。

## 一、证据链（逐条取自日志与文件，非推断）

1. CI：run `40151828387`（master `05d6793f` = #233）→ job `DAST - OWASP ZAP Baseline (gateway)`
   `conclusion=failure`，失败步骤「启动依赖并诊断容器状态」。
2. 同一 job 日志原文两条：
   ```
   01:54:32  dependency failed to start: container nexuschain-nexus-core-1 is unhealthy
   01:57:32  nexus-core-1 | WARN [o.n.c.LocalRpcInterceptor] Local-only RPC rejected: missing token from 127.0.0.1:53874
   ```
   第二条是 #233 新增的 `reject()` 文案（旧文案是 `The local connection has been started,Illegal connection request …`），
   所以"拒绝发生在 #233 之后"这件事有直接文本证据。
3. 为什么会打死探针：`nexus.localonly=${LOCALONLY:true}`（默认开），`LocalRpcInterceptor`
   挂 `/**`，而原白名单里没有 `/height`（现查 `WebSecurityConfig:41` 的 `excludePathPatterns(...)`，
   只有 `/consensus/*` 与 `/NexusChainCore/*`，且 `/consensus/blocks`、`/consensus/status` 各重复写了两遍）。
4. 探针路径确认是 `/height`：
   - `docker-compose.yml` core `healthcheck: … wget http://localhost:19585/height`
   - `deploy/helm/charts/nexus-core/templates/statefulset.yaml:136-147` **liveness 与 readiness 都**
     `httpGet path: /height`
   - `deploy/` 与 helm 全仓 grep `LOCALONLY` **零命中** ⇒ k8s 侧也走默认 true。

## 二、为什么 #233 之前"看起来是好的"

旧实现 `return false` 不写响应 ⇒ 容器内探针拿到 **200 空 body**，`wget -q -O /dev/null` 成功 ⇒ healthy。
这正是 PLAN-032 记下的那个静默失效形状（当时表现为"对端拉到空数组"）。
#233 把拒绝显式化是正确的加固；显式化之后，**同一条漏配立刻变成可见的故障**。
所以这不是"回退 #233"的理由，而是"补白名单"的理由。

## 三、热修做了什么

- `WebSecurityConfig`：白名单抽成 `LOCAL_ONLY_PUBLIC_PATHS`，加 `/height`，去掉重复项；
  注释写明"探针 + P2P 两条历史踩坑 ⇒ 新增这类路径必须同步这里"。
- 新用例 `WebSecurityConfigWhitelistTest`（6 例）钉的是 **`AntPathMatcher` 匹配语义**，
  不是"数组里含这个字符串"：`/heights`、`/proxy/height`、`/consensus` 必须**未命中**；
  并反向钉住未列出的命令端点（`/getTransactionHeight`、`/transactions`、`/`）**不得被顺手放行**。

## 四、请复核/请裁的点

1. **`/height` 免鉴权是否可接受？** 它只返回一个整数（`CommandController:202`），
   与已经公开的 `/consensus/status`、`/consensus/blocks` 同类。若安全侧要求 core 探针带凭据，
   那要改的是探针本身（compose + 两个 helm 模板），工作量与影响面都比现在大——**请给个方向**。
2. **DAST 不在 required 里** ⇒ "PR 8/8 绿"与"主干绿"不是一回事，这笔就是一个活例。
   是否要把 DAST 升为 required（或加一条"core 容器健康"的最小门禁进 PR 流水线）？
   这是 CI 配置改动，**我不自行动**，请 orchestrator 定。
3. 同类漏配是否还有：helm 里其他 chart 的探针路径是否都落在各自服务的白名单内——
   我只查了 core（compose/k8s 探针与 `LocalRpcInterceptor` 只作用在 core）。

## 五、边界

- 只改 `WebSecurityConfig` + 新增测试 + CHANGELOG + 本留言；**没动 `LocalRpcInterceptor` 的 401 语义**，
  没动别人的分支或已合并的 PR。
- 未核：core 之外是否还有依赖 `LocalRpcInterceptor` 的探针路径（见 §四第 3 点）。
