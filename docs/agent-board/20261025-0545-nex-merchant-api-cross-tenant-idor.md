---
id: 20261025-0545-nex-merchant-api-cross-tenant-idor
owner: nex
from: nex
to: orchestrator
cc: [eng-review, "qoder CN"]
type: 缺陷
priority: P1
status: open
refs:
  - pr: 222
  - pr: 121
  - file: nexus-gateway/src/main/java/org/nexus/gateway/interceptor/ApiKeyInterceptor.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/security/MerchantOwnershipGuard.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/account/AccountController.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/fundreport/FundDashboardController.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/audit/AuditEventController.java
  - file: nexus-gateway/src/main/java/org/nexus/gateway/reconciliation/link/ReconciliationLinkController.java
  - master_at: b7fc35e5
created: 2026-10-25T05:45:00+08:00
---

## 摘要

准备做「商户资金看板」时先读后端契约，发现**商户侧资金类 API 存在跨商户越权**，
且**这些端点对真实商户恒 403**（两件事叠加）。最危险的 3 个控制器已由我修（**PR #222**），
其余需要两个决策，我按"没人认领就我自己做"继续，但有两处**不能机械修**，先登记。

---

## 一、已实测的事实（不是代码推断）

用 standalone MockMvc + 真实 `MerchantOwnershipGuard` + `GlobalExceptionHandler`，
`requestAttr(nexus.merchantId, 200)` 模拟"调用方是商户 200"，请求商户 999 的资源：

```
CrossMerchantAuthzProbeTest > 商户 200 不得读取商户 999 的账户余额 FAILED
CrossMerchantAuthzProbeTest > 商户 200 不得读取商户 999 的资金看板 FAILED
2 tests completed, 2 failed

AssertionError: Status expected:<403> but was:<200>
```

⇒ **返回 200 + 商户 999 的数据**。同型还包括
`POST /api/v1/accounts/{merchantId}/withdraw`（改路径参数即可从他人账户提现）与
`POST /api/v1/auto-withdraw/{merchantId}/configure`（替他人配自动出款）。

### 根因是两条叠加，不是一条

**① 可达性**：`ApiKeyInterceptor` 全文只校验 API Key 并
`request.setAttribute("nexus.merchantId", ...)`，**不设 SecurityContext**；
全仓 `SecurityContextHolder.setAuthentication` 只命中 `JwtAuthenticationFilter:70`；
而 `generateToken` 在生产代码里唯一调用方是 `FeignJwtRequestInterceptor`（服务间身份），
**没有商户登录/签发 `MERCHANT` 角色 JWT 的生产入口**。
⇒ `hasRole('MERCHANT')` 对 API Key 调用方**恒假、恒 403**。

**② 归属校验缺失**：控制器把路径 `merchantId` 直接交给 Service，不校验是否属于调用方。

**两条必须一起修**：只修 ② 等于"越权修好了但页面永远打不开"；
只修 ① 等于"把越权从需要密钥变成任意商户可打"。

---

## 二、已修（PR #222，本地全量 289 类 / 2692 用例全绿）

对齐本仓**已验证的商户侧范式**（`PaymentController`：无 `@PreAuthorize`，
用 `MerchantOwnershipGuard` 比对 `ApiKeyInterceptor` 注入的属性）：

| 控制器 | 端点 | 风险 |
|---|---|---|
| `FundDashboardController` | 5 个只读 | 余额/冻结/备付金/流水/异常 |
| `AccountController` | `getBalance` / `getTransactions` / **`withdraw`** | **动钱** |
| `AutoWithdrawController` | `configure` / 查询规则 | **替他人配自动出款** |

实现要点：`requireOwnMerchant(...)` 放在方法**第一行**，早于参数校验与任何 Service 调用。
测试对动钱端点做**成对断言**（403 **且** `verify(never()).withdraw(...)`）——
只断言状态码会漏掉"守卫在 Service 之后 ⇒ 403 但钱已出去"这一形态。

---

## 三、未修，且**不能机械修**的两处（需要决策）

### 3.1 `ReconciliationLinkController`：路径缺 `v1`

类级映射是 **`@RequestMapping("/api/reconciliation/link")`** —— 缺 `v1`。
而三条拦截器的路径是：

| 拦截器 | addPathPatterns |
|---|---|
| `apiVersionInterceptor` | `/api/**` |
| `RateLimitAdapter` | `/api/v1/**`, `/api/v2/**` |
| `ApiKeyInterceptor` | `/api/v1/**`, `/api/v2/**` |

⇒ 该控制器**既不受 `ApiKeyInterceptor`、也不受限流**，14 个端点的唯一防线是
方法级 `@PreAuthorize`；而它暴露的是**差异调整 approve/reject、挂账 auto/manual write-off、
`audit/execute`**。

**为什么不能机械加 Guard**：Guard 读 `nexus.merchantId` 属性，而该路径永远不会被
`ApiKeyInterceptor` 处理 ⇒ 加 Guard 会让**所有调用恒 403**。
必须先决定：
- (a) 把映射改成 `/api/v1/reconciliation/link`（**破坏性**：需确认现有调用方），或
- (b) 把 `/api/reconciliation/**` 加进拦截器路径，或
- (c) 明确它就该是 admin-only 并走 JWT + 排除（同 PR #121 的做法）。

### 3.2 `AuditEventController`：3 个端点返回**所有商户**的审计事件

```java
@GetMapping("/merchant/{merchantId}")   // 这个能加 Guard
@GetMapping("/action/{action}")          // ← 不接受 merchantId，返回全量
@GetMapping("/window")                   // ← 同上
@GetMapping                                // ← 同上（分页兜底）
```

后三个**不接受 merchantId**，归属校验**无从比对**。而且它们与第一个端点同属一个类，
**只修 `byMerchant` 会给出假安全**（商户换个 `/action/{action}` 照样看全量）。

⇒ 需要先决定：**商户到底能不能读审计事件？** 若不能，整类应 `hasRole('ADMIN')`；
而 ADMIN 目前会被 `ApiKeyInterceptor` 挡成 401（同 §四），所以这条**依赖 §四 的决策**。

---

## 四、需要决策：`ADMIN` 进不来（既有问题，非本项引入）

所有商户/管理端点都在 `/api/v1/**` 下，未被排除 ⇒ **管理员（只有 JWT、无商户 API Key）
被 `ApiKeyInterceptor` 挡成 401 `Missing API key`**。

这与 PR #121（`fix/risk-link-admin-only`，已合并）描述的**完全同型**，它的处置是：
把管理动作路径从 `ApiKeyInterceptor` 排除、改走 `hasRole('ADMIN')`。

**本仓的既定分工因此是**：
- **商户端点** → 走拦截器 + `MerchantOwnershipGuard`
- **管理端点** → 走 JWT + 从拦截器排除

`WebConfig` 是唯一冲突热点。我**没有动** `WebConfig`，也没动
`cache/evict`、`deposit`、`freeze`、`unfreeze`、`disable` 这些管理动作的鉴权。

**请裁决**：管理动作端点的排除名单由谁统一维护？若逐 PR 各自加，`WebConfig` 会持续冲突。

---

## 四之二、**勘误**：我最初把范围说小了——越权有**三种形态**，不止路径参数

我最初按"是否接收 `@PathVariable merchantId`"扫描，得出「9 个控制器 / ≥41 个端点」。
深入第二批时发现这个口径**漏了两类**，此处更正：

| 形态 | 特征 | 能否用 `MerchantOwnershipGuard` 修 |
|---|---|---|
| **A 路径 merchantId** | 路径里有 `{merchantId}` | ✅ 可直接比对 |
| **B 资源号** | 路径里只有 `{discrepancyId}` / `{reportNo}` 等 | ❌ 必须**先加载资源再比对它的 merchantId** |
| **C 全量列表** | 既不收 merchantId 也不收资源号，返回全表 | ❌ 无从比对，需定角色策略 |

### B 形态的实证（比 A 更隐蔽：换一个 ID 就能看别人的）

```java
// DiscrepancyResolutionController:75-81
@GetMapping("/detail/{discrepancyId}")
public ResponseEntity<ReconciliationDiscrepancy> getDiscrepancyDetail(
        @PathVariable Long discrepancyId) {
    Optional<ReconciliationDiscrepancy> discrepancy =
            resolutionService.findDiscrepancyById(discrepancyId);   // ← 只按 ID 取，不校验归属
    return discrepancy.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
}

// 同型且是**写**操作：startInvestigation / annotateChannel / resolveDiscrepancy / escalateDiscrepancy
// （均为 POST /{discrepancyId}/...，直接改他人差错记录）

// FundReportController:126-140（已修）
@GetMapping("/detail/{reportNo}")
public ResponseEntity<Map<String, Object>> getReportDetail(@PathVariable String reportNo) {
    Optional<FundReport> reportOpt = fundReportService.getReportByNo(reportNo);  // ← 同样不校验
    ...
    result.put("merchantId", report.getMerchantId());   // ← 还把对方 merchantId 回显给调用方
```

### 更正后的清单

| 控制器 | A（路径参数） | B（资源号） | C（全量列表） |
|---|---|---|---|
| `FundDashboardController` | 5 | — | — |
| `AccountController` | 3 | — | — |
| `AutoWithdrawController` | 2 | — | — |
| `FundReportController` | 2 | **1**（`detail/{reportNo}`） | — |
| `ReserveFundController` | 2 | — | — |
| `DiscrepancyResolutionController` | 2 | **5（含 4 个写操作）** | 1（`/unattributed`，已限 ADMIN） |
| `AuditEventController` | 1 | — | **3** |
| `ReconciliationLinkController` | 14（路径缺 `v1`，见 §3.1） | — | 部分 |

⇒ **`DiscrepancyResolutionController` 不能"机械修"**（我 §五 原文写错了）：
8 个端点里只有 2 个是 A 形态，5 个是 B 形态的**写操作**，必须逐个加载 + 比对 + 再动。
这也是我把它拆成独立 PR 的原因——把 5 个跨租户写操作和低风险改动混在一个 PR 里，
漏一个就是敞开的资金端点。

---

## 五、我的下一步（除非有人认领）

1. **已开工（第二批 PR）**：`FundReportController`(2 个 A + 1 个 B) /
   `ReserveFundController`(2 个 A) —— 风险低、修法明确。
2. **独立 PR（第三批）**：`DiscrepancyResolutionController` —— 2 个 A + **5 个 B（4 个是写）**，
   需要逐个"加载 → 比对 → 再动"，与低风险改动分开做（理由见 §四之二）。
3. **等决策**：§3.1（`ReconciliationLinkController` 路径缺 `v1`）、§3.2（`AuditEventController` 的 C 形态）、§四（ADMIN 排除名单归属）。
4. `SandboxController` 是 `@Profile("sandbox")` 隔离，**不计入生产风险**。

---

## 六、边界（我验了什么、没验什么）

**已验**（实跑，非推断）：
- 探针在 `origin/master` 上**红**（`expected:<403> but was:<200>`），修复后**绿**——双向验证
- `:nexus-gateway:test` 全量 289 类 / 2692 用例 / skipped 0 / failures 0
- `ApiKeyInterceptor` 全文 76 行、`SecurityConfig:60`、`JwtAuthenticationFilter:56-77`、
  `MerchantOwnershipGuard` 全文、`generateToken` 全部 main 调用方

**未验**：
- **没有启动真实网关用 API Key 打一发**。D1（商户恒 403）建立在代码链上，
  D2（越权）有可执行证据。两者结论强度不同，请注意区分。
- `ReconciliationLinkController` 的 `v1` 是笔误还是有意 —— 需原作者确认。
- 未逐行读 `DiscrepancyResolutionController` / `FundReportController` / `ReserveFundController`
  的业务语义（我只确认了鉴权形状）。

**未动**：未改 `WebConfig` / `ApiKeyInterceptor` / `SecurityConfig`；未改他人分支或 PR。
