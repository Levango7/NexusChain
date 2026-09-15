/**
 * 后端真实字段形状的测试夹具。
 *
 * ⚠️ 本文件的字段名必须与后端**逐字一致**，修改前必须核对来源。
 *    页面测试若使用「前端想象中的形状」，就会重演 2026-09-16 审查发现的
 *    P0 —— 类型断言通过、测试全绿，但运行期字段恒为 undefined。
 *
 * 来源：
 *   NODE_STATUS      ← nexus-core/…/controller/JsonRpcController#doGetNodeStatus
 *   RPC_BLOCK        ← nexus-core/…/controller/JsonRpcController#toRpcBlock
 *   RPC_TRANSACTION  ← nexus-core/…/controller/JsonRpcController#toRpcTransaction
 *   ACCOUNT_INFO     ← nexus-explorer/backend/src/index.ts  GET /api/address/:addr
 *   ORCH_*           ← nexus-gateway/…/PaymentOrchestrationController
 */

export const NODE_STATUS = {
  chainId: 1,
  latestHeight: 1234,
  latestHash: "ab".repeat(32),
  syncing: false,
  peers: 0,
  version: "v2-rpc-bridge",
} as const;

export const RPC_BLOCK = {
  height: 1234,
  hash: "cd".repeat(32),
  parentHash: "ef".repeat(32),
  timestamp: 1757000000,
  txCount: 3,
  proposer: "NEXproposerAddress",
  transactions: ["11".repeat(32)],
} as const;

export const RPC_TRANSACTION = {
  txHash: "11".repeat(32),
  blockHeight: 1234,
  from: "NEXfromAddress",
  to: "NEXtoAddress",
  amount: "100",
  status: "success",
  timestamp: 1757000000,
  data: null,
} as const;

export const ACCOUNT_INFO = {
  address: "NEXaddress",
  balance: "1000",
  txCount: 5,
} as const;

/* ---------- gateway 编排端点（snake_case）---------- */

export const ORCH_PAYMENT = {
  id: "pay_abc123",
  status: "SUCCEEDED",
  amount: 100,
  currency: "NEX",
  description: "test payment",
  connector: "chain",
  connector_payment_id: "cp_1",
  transaction_hash: "22".repeat(32),
  routing_strategy: "priority",
  latency_ms: 120,
  cost_bps: 150,
  created_at: "2026-09-16T10:00:00Z",
  confirmed_at: "2026-09-16T10:00:05Z",
  expires_at: null,
} as const;

export const ORCH_PAYMENT_LIST = {
  data: [ORCH_PAYMENT],
  total: 1,
  page: 0,
  limit: 20,
} as const;

export const ORCH_CONNECTOR = {
  id: "chain",
  type: "http_psp",
  display_name: "Chain Connector",
  active: true,
  fee_bps: 150,
  currencies: ["NEX"],
} as const;

export const ORCH_RULE = {
  id: "rule-1",
  name: "default",
  conditions: {},
  strategy: "PRIORITY",
  connectors: ["chain"],
  priority: 1,
} as const;
