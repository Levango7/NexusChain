/**
 * 区块浏览器数据模型 —— 与后端实际返回字段**严格对齐**。
 *
 * ── 字段来源（修改任何字段前必须先核对后端）────────────────────────────
 *   ChainStatus     ← nexus-core/…/controller/JsonRpcController#doGetNodeStatus
 *   BlockInfo       ← nexus-core/…/controller/JsonRpcController#toRpcBlock
 *   TransactionInfo ← nexus-core/…/controller/JsonRpcController#toRpcTransaction
 *   AccountInfo     ← nexus-explorer/backend/src/index.ts  GET /api/address/:addr
 * ────────────────────────────────────────────────────────────────────────
 *
 * ⚠️ 后端（nexus-explorer/backend）对 core 的 RPC 结果**纯透传**，不做字段映射。
 *    因此本文件的字段名必须与后端逐字一致，且**不得声明后端未返回的字段**。
 *
 * 历史教训（2026-09-16 审查 P0）：本文件曾声明后端不存在的字段 ——
 * `BlockInfo.difficulty/size`、`TransactionInfo.blockHash/fee/type/typeName/nonce/payload`、
 * `ChainStatus.height/network/tps`、`AccountInfo.publicKeyHash/nonce`。
 * 由于 `request<T>()`（api/client.ts）是**无运行时校验的类型断言**，类型系统在此处
 * 完全失效，这些字段运行期恒为 `undefined`：
 *   - `status.height.toLocaleString()` → TypeError，整页被 ErrorBoundary 替换
 *   - `{block.size} 字节` / `{tx.fee} NEX` → 渲染为字面量 "undefined" 或空白数值
 *     （React 把 undefined 子节点渲染为空串，用户无法区分「值为 0」与「字段缺失」）
 * 因此新增字段前务必先确认后端确实返回该字段。
 *
 * 同时已删除两个零引用类型：`TxRecord`（字段为 snake_case 且无消费方）、
 * `PaginatedResponse<T>`（无消费方）。
 */

/**
 * 节点状态（`GET /api/node/status`）。
 *
 * 注意：`peers` 与 `syncing` 目前是 core 侧的**桩值**（恒为 0 / false），
 * `version` 为常量 `"v2-rpc-bridge"`；仅 `chainId` / `latestHeight` / `latestHash`
 * 为真实数据。UI 不应把 peers 当作可用监控指标。
 */
export interface ChainStatus {
  chainId: number;
  latestHeight: number;
  latestHash: string;
  syncing: boolean;
  peers: number;
  version: string;
}

/** 区块（`GET /api/blocks`、`GET /api/blocks/:height`）。 */
export interface BlockInfo {
  height: number;
  hash: string;
  parentHash: string;
  /** 区块时间，Unix 秒。 */
  timestamp: number;
  txCount: number;
  /** 出块者地址（core 侧以 coinbase 交易的 to 地址近似）。 */
  proposer: string;
  /** 区块体内交易的哈希列表（十六进制）。 */
  transactions: string[];
}

/** 交易（`GET /api/tx`、`GET /api/tx/:hash`）。 */
export interface TransactionInfo {
  txHash: string;
  blockHeight: number;
  from: string;
  to: string;
  /** 金额，后端以字符串返回（避免大整数精度问题）。 */
  amount: string;
  /**
   * 交易状态。core 侧目前对已上链交易**恒返回 `"success"`**
   * （`toRpcTransaction` 硬编码），保留联合类型以兼容未来扩展。
   */
  status: "success" | "failed" | "pending";
  /** 所在区块时间，Unix 秒。 */
  timestamp: number;
  /** 十六进制编码的交易负载；无负载时为 null。 */
  data: string | null;
}

/**
 * 地址信息（`GET /api/address/:addr`）。
 *
 * 后端仅返回这三个字段 —— 不包含 `publicKeyHash` / `nonce`。
 */
export interface AccountInfo {
  address: string;
  balance: string;
  txCount: number;
}
