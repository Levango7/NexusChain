/**
 * 支付编排 API 数据模型（gateway `/api/v1/payments/**`）。
 *
 * ── 为什么单独一个文件？────────────────────────────────────────────────
 * 本组端点由 `nexus-gateway` 的 `PaymentOrchestrationController` 提供，其
 * `toResponse` 手工构造 **snake_case** 响应体；而区块浏览器主数据链路
 * （`types/index.ts`）来自 nexus-core 的 JSON-RPC 桥接，使用 **camelCase**。
 * 两套命名属于不同后端服务的既有契约，前端必须各自如实建模，不能强行统一 ——
 * 强行改写任一端的字段名都会破坏对应服务。
 *
 * 命名约定边界（P2 修复）：
 *   - 本文件（`types/orchestration.ts`）→ gateway 编排端点，snake_case
 *   - `types/index.ts`                    → nexus-core RPC 桥接，camelCase
 *   - 例外：`/connectors/{id}/health` 直接序列化 `ConnectorHealth` 对象，
 *     Jackson 默认输出 **camelCase**（与同控制器的 snake_case 不一致）。
 *     这是后端既有行为，前端按实际建模并在下方标注。
 * ────────────────────────────────────────────────────────────────────────
 *
 * 字段来源：`nexus-gateway/…/orchestration/controller/PaymentOrchestrationController.java`
 *   toResponse()      → OrchestratedPaymentDto
 *   listPayments()    → PaymentListResponse
 *   listConnectors()  → ConnectorDto
 *   listRules()       → RoutingRuleDto
 */

/** 支付状态（`OrchPaymentStatus` 枚举名）。 */
export type OrchestrationPaymentStatus =
  "PENDING" | "PROCESSING" | "SUCCEEDED" | "FAILED" | "EXPIRED" | string;

/** 单笔编排支付（`toResponse`，snake_case）。 */
export interface OrchestratedPaymentDto {
  id: string;
  status: OrchestrationPaymentStatus;
  amount: number;
  currency: string;
  description: string | null;
  /** 实际承接该笔支付的连接器 id。 */
  connector: string | null;
  connector_payment_id: string | null;
  /** 链上交易哈希；未上链时为 null。 */
  transaction_hash: string | null;
  /** 路由策略：显式指定为 `"explicit"`，否则为 `"priority"`。 */
  routing_strategy: string | null;
  latency_ms: number | null;
  cost_bps: number | null;
  /** ISO-8601 时间戳字符串。 */
  created_at: string | null;
  confirmed_at: string | null;
  expires_at: string | null;
}

/** `GET /api/v1/payments` 响应（注意是 `limit` 而非 `pageSize`）。 */
export interface PaymentListResponse {
  data: OrchestratedPaymentDto[];
  total: number;
  page: number;
  limit: number;
}

/** 支付连接器（`listConnectors`，snake_case）。 */
export interface ConnectorDto {
  id: string;
  /** 连接器类型，目前动态注册仅支持 `"http_psp"`。 */
  type: string;
  display_name: string;
  active: boolean;
  /** 费率，基点（1 bp = 0.01%）。 */
  fee_bps: number;
  /** 支持的币种；空数组表示全部。 */
  currencies: string[];
}

/**
 * 连接器健康检查（`GET /api/v1/payments/connectors/{id}/health`）。
 *
 * ⚠️ 该端点直接序列化 `ConnectorHealth` 对象，字段为 **camelCase**，
 * 与同控制器的其他响应（snake_case）不一致。前端按实际字段建模。
 */
export interface ConnectorHealthDto {
  connectorId: string;
  healthy: boolean;
  message: string | null;
  /** ISO-8601 时间戳字符串。 */
  checkedAt: string | null;
  latencyMs: number;
}

/** 路由策略（`RoutingStrategy` 枚举名）。 */
export type RoutingStrategyName = "PRIORITY" | "COST" | "LATENCY" | "AI" | string;

/** 路由规则（`listRules`，直接序列化 `RoutingRule`，字段为 camelCase）。 */
export interface RoutingRuleDto {
  id: string;
  name: string;
  /** 匹配条件（键值对）。 */
  conditions: Record<string, string>;
  strategy: RoutingStrategyName;
  /** 候选连接器 id 列表，按顺序尝试。 */
  connectors: string[];
  priority: number;
}
