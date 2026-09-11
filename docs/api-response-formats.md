# NexusChain API 响应格式现状与演进约定

> 2026-09-11 质量审查 B2b 文档标注——如实记录各模块当前的响应格式差异，
> 作为 SDK 调用方与后续 API 统一治理（v3）的基线。**本文档只记录事实，
> 不改变任何现有契约。**

## 现状：错误响应格式并存清单（对外契约，勿随意改动）

| 来源 | 成功形状 | 失败形状 | 说明 |
|---|---|---|---|
| gateway v1（多数 Controller） | 裸实体/裸 Map（**无统一信封**） | `ApiResponse{code:数字, message, data, traceId}` | `ApiResponse` 注释宣称"统一结构"但**只有异常处理器在用**——成功路径从不用它 |
| gateway v2 | 裸实体 | `V2ErrorResponse{error:{code:字符串, message, details}}` | v2 自有错误信封（code 是字符串如 `"ILLEGAL_STATE_TRANSITION"`） |
| gateway 手工错误 | — | 各处 `{"error": "..."}` | CheckoutController 等少量手工 Map |
| api-gateway 限流器 | — | `{"code":"RATE_LIMITED", "message":...}` | RateLimitFilter 专用 |
| signing-service | `{statusCode:2000, data, message}` | 业务失败：**HTTP 200 + `{statusCode:5000,...}`**（TxController 既有约定）；B2a 起：参数类错误 HTTP 400 + `{statusCode:4000,...}` | HTTP 状态码与业务码双轨——调用方需两层判定 |
| wallet-service | 裸 Map/实体 | B2a 起：HTTP 4xx/5xx + `{"error":"..."}` | 之前落 Spring 默认 error 页 |
| nexus-core | `APIResult{code:2000, message, data}` | B2a 起：HTTP 4xx/5xx + `APIResult{code, message}` | 之前落 Spring 默认 error 页 |

## 字段命名现状

同网关内 snake_case 与 camelCase 混用：`WebhookDeliveryController` 用
`delivery_id/payment_id`，`OrderV2Controller` 用 `chain_tx_hash`，
`CheckoutController` 用 `orderNo`。**v3 前不改**（改即破坏契约）。

## 调用方指引（当前如何写健壮的客户端）

1. **gateway v1/v2**：先判 HTTP 状态码；错误体形状按端点版本分支
   （v1 数字 code / v2 字符串 error.code）
2. **signing-service**：不能只判 HTTP 200——**必须解析 body 的
   `statusCode`**（2000 成功 / 5000 业务失败 / 4000 参数错误）
3. **wallet/core**：HTTP 状态码语义准确；body 形状按上表

## v3 演进方向（未排期，仅记录）

- 单一错误信封（RFC 7807 `application/problem+json` 是候选——标准化且
  可扩展 fields）
- 成功路径是否加信封需权衡：加信封破坏 v1/v2 现有调用方；不加则维持
  "成功裸 / 失败信封"的现行惯例
- 字段命名统一 camelCase（新端点起），存量端点冻结

## 历史修复记录

- 2026-09-10 Top1：Feign `signTransfer` 契约错配根治（String → 真实
  Map + `SigningResponses.txHash` 提取器）——教训：**跨服务 Feign 契约
  必须有真实联调测试，纯 mock 测试会掩盖反序列化失败**
- 2026-09-11 B7：`AccessDeniedException` 业务码 40000 → `ACCESS_DENIED(40301)`，
  与 HTTP 403 对齐
- 2026-09-11 B2a：signing/wallet/core 补全局异常处理器（参数类错误
  400 语义化；兜底 500 带服务端日志、响应不带堆栈）
