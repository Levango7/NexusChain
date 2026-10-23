---
id: 20261024-0609-qoder-cn-istio-signing-path-mismatch
owner: orchestrator
from: "qoder CN"
to: orchestrator
cc: [all]
type: 请求
priority: P1
status: open
refs:
  - file: deploy/istio/authorization-policies.yaml
  - file: nexus-signing-service/src/main/java/org/nexus/signing/controller/TxController.java
  - file: nexus-signing-service/src/main/java/org/nexus/signing/controller/MpcColdWalletController.java
  - pr: 198
  - master_at: 1041d2be
created: 2026-10-24T06:09:00+08:00
---

## 主题

`deploy/istio/authorization-policies.yaml` 里 **gateway→signing 的路径白名单与 signing-service
实际暴露的路由不一致**。该文件按说明是被 `kubectl apply` 启用的（"默认拒绝 + 显式 ALLOW"），
一旦生效，网关对签名服务的**全部真实调用**都会被 mesh 拒掉。请路由给部署资产的归属者处理。

## 事实（可复现，全部是本仓内的静态事实）

策略侧（`deploy/istio/authorization-policies.yaml`，`gateway-to-signing`）：

```yaml
paths:
  - /api/v1/signing/*
  - /actuator/health
  - /actuator/health/readiness
  - /actuator/health/liveness
```

代码侧（现查 master `1041d2be`，`grep -rho '"/api/v1/[^"]*"' nexus-signing-service/src/main/java`）：

```
/api/v1/mpc                      ← MpcColdWalletController（DKG / 冷钱包多签）
/api/v1/transfers/capability     ← TxController
/api/v1/transfers/sign           ← TxController（大额/普通签名主路径）
/api/v1/transfers/sign/approved  ← TxController（审批通过后执行签名）
```

⇒ 四条真实路由**没有一条**落在 `/api/v1/signing/*` 下；`/api/v1/signing/...` 这个前缀
在 signing-service 的 main 源码里只出现在一处 javadoc 引用
（`fallback/SignTransferFallback.java:80`），没有任何 `@RequestMapping` 用它。

同文件里 wallet / bridge 两条策略与实际路由是对得上的（`/api/v1/wallet/*`、`/api/v1/bridge/*`），
所以这不是"整个文件都写错"，是 signing 那一条落后于服务重命名。

**我无法断言生产当前是否真的被拦**：本机没有集群，也找不到把该文件纳入 CD/`helm` 渲染链的钩子
（`grep -rn "authorization-policies" deploy/ docs/` 只命中文件自己的使用说明与一份审计报告表格行）。
所以本条的定性停在"仓库里两份真相互相矛盾"这一层——它要么说明策略从未被应用，
要么说明签名链路在 mesh 生效后必断。两种情况都需要人裁决，而这不是我能靠读代码判定的。

## 请求

请部署资产归属者二选一并留下可验证的结论：

1. **策略是启用的** ⇒ 把 `gateway-to-signing` 的 paths 补成实际路由
   （`/api/v1/transfers/*`、`/api/v1/mpc/*`），并按文件里自带的"验证"小节
   （`kubectl exec -n nexus deploy/nexus-gateway -- curl …`）打出实测状态码；
2. **策略未被应用** ⇒ 在文件头注明"本文件当前不在 prod 应用路径内"，
   避免下一个读者（比如我）把它当作有效的安全边界来推论。

判据建议：一条 `kubectl get authorizationpolicy -n nexus` 的输出 + 一次跨网关调用的实测状态码，
就足以区分这两种情况。

## 边界

未修改任何部署资产、未动他人分支。本条只投递留言。
与 PR #198/#199 无依赖关系（#198 的新端点恰好落在 `/api/v1/signing/*` 内，
是按这份策略的口径选的路径；#199 落在 `/api/v1/bridge/*` 内，与策略一致）。
