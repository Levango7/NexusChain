/**
 * NexusChain k6 测试数据生成工具
 * ============================================================================
 *
 * 为 4 个压测场景提供随机但合法的请求体：
 *   - paymentCreateBody()   → POST /api/v1/payments
 *   - bridgeLockBody()      → POST /api/v1/bridge/lock
 *   - webhookConfirmBody()  → POST /api/v1/payments/{id}/confirm
 *   - 钱包地址 / 链 ID / 哈希 等辅助生成器
 *
 * 字段规范对应服务端 DTO：
 *   - PaymentOrchestrationController.createPayment(Map)：
 *       merchant_id, amount, currency, description, notify_url, metadata,
 *       request_id, routing.preferred_connector
 *   - LockRequest：sourceChainId, targetChainId, amount(long, 最小单位),
 *       userAddress, targetAddress, sourceTxHash, timestamp, memo
 *
 * 注意：所有随机数基于 Math.random()，k6 不要求密码学强度；如需可重现，
 * 可通过 -e RANDOM_SEED=xxx 注入并替换 rng()。
 */

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------
const DEFAULT_MERCHANT_ID = __ENV.MERCHANT_ID || "1";
const DEFAULT_NOTIFY_URL =
  __ENV.NOTIFY_URL || "https://perf.example.com/webhooks/nexus";
const DEFAULT_PREFERRED_CONNECTOR =
  __ENV.PREFERRED_CONNECTOR || "mock"; // 压测默认走 mock 连接器，避免真实链上延迟

// 币种池：NEX（主）、USD、USDT、ETH、BTC
const CURRENCIES = ["NEX", "NEX", "NEX", "USD", "USDT", "ETH", "BTC"];

// 支持的源/目标链（与 nexus-bridge BridgeService 注册一致）
const CHAIN_IDS = ["ethereum", "bsc", "polygon", "arbitrum", "optimism"];

// ---------------------------------------------------------------------------
// 基础随机工具
// ---------------------------------------------------------------------------
function rngInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

function rngChoice(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function rngHex(len) {
  const chars = "0123456789abcdef";
  let s = "0x";
  for (let i = 0; i < len; i++) s += chars[Math.floor(Math.random() * 16)];
  return s;
}

/**
 * 模拟 EVM 钱包地址（0x + 40 hex）。
 */
export function randomWalletAddress() {
  return rngHex(40);
}

/**
 * 模拟 64 字节交易哈希（0x + 64 hex）。
 */
export function randomTxHash() {
  return rngHex(64);
}

/**
 * UUIDv4 风格的 request_id（用于幂等键）。
 */
export function randomRequestId() {
  const hex = "0123456789abcdef";
  const seg = (n) => {
    let s = "";
    for (let i = 0; i < n; i++) s += hex[Math.floor(Math.random() * 16)];
    return s;
  };
  return `${seg(8)}-${seg(4)}-4${seg(3)}-a${seg(3)}-${seg(12)}`;
}

/**
 * 思考时间（毫秒），均匀分布于 [min,max]。
 * 用于在 VU 内模拟用户读屏间隔，避免过密打满连接池。
 */
export function thinkTime(minMs = 100, maxMs = 300) {
  return rngInt(minMs, maxMs);
}

// ---------------------------------------------------------------------------
// 支付创建请求体
// ---------------------------------------------------------------------------
/**
 * 生成 POST /api/v1/payments 请求体。
 *
 * @param {object} opts 覆盖项（可选）
 * @returns {object}    可 JSON.stringify 的请求体
 */
export function paymentCreateBody(opts = {}) {
  // 金额：1 ~ 10000 NEX（最小单位 1 = 1e-18 NEX，这里用整数最小单位）
  // 取 100 ~ 10_000_000 区间，覆盖小额高频与中额场景
  const amount = opts.amount !== undefined ? opts.amount : rngInt(100, 10_000_000);
  const currency = opts.currency || rngChoice(CURRENCIES);

  return {
    merchant_id: opts.merchantId || DEFAULT_MERCHANT_ID,
    amount: amount,
    currency: currency,
    description:
      opts.description || `k6 perf payment ${currency} ${amount}`,
    notify_url: opts.notifyUrl || DEFAULT_NOTIFY_URL,
    metadata: opts.metadata || `{"source":"k6","vu":"${typeof __VU !== "undefined" ? __VU : 0}"}`,
    request_id: opts.requestId || randomRequestId(),
    routing: {
      preferred_connector: opts.preferredConnector || DEFAULT_PREFERRED_CONNECTOR,
    },
  };
}

// ---------------------------------------------------------------------------
// 桥锁定请求体
// ---------------------------------------------------------------------------
/**
 * 生成 POST /api/v1/bridge/lock 请求体。
 * 字段严格对齐 LockRequest.java：
 *   sourceChainId, targetChainId, amount(long), userAddress, targetAddress,
 *   sourceTxHash, timestamp, memo
 */
export function bridgeLockBody(opts = {}) {
  // 源/目标链必须不同
  let sourceChainId = opts.sourceChainId || rngChoice(CHAIN_IDS);
  let targetChainId = opts.targetChainId || rngChoice(CHAIN_IDS);
  if (sourceChainId === targetChainId) {
    // 简单避免同链：循环选下一个
    const idx = CHAIN_IDS.indexOf(targetChainId);
    targetChainId = CHAIN_IDS[(idx + 1) % CHAIN_IDS.length];
  }

  // 锁定金额：1 ~ 1000 NEX 最小单位（10^18 进制，这里用整数表示）
  const amount = opts.amount !== undefined ? opts.amount : rngInt(1_000_000, 1_000_000_000);

  return {
    sourceChainId: sourceChainId,
    targetChainId: targetChainId,
    amount: amount,
    userAddress: opts.userAddress || randomWalletAddress(),
    targetAddress: opts.targetAddress || randomWalletAddress(),
    sourceTxHash: opts.sourceTxHash || randomTxHash(),
    timestamp: Date.now(),
    memo: opts.memo || `k6 bridge lock ${sourceChainId}->${targetChainId}`,
  };
}

// ---------------------------------------------------------------------------
// Webhook 回调（支付确认）请求体
// ---------------------------------------------------------------------------
/**
 * 生成 POST /api/v1/payments/{id}/confirm 请求体。
 * 服务端 PayController.ConfirmRequest：{ chainTxHash }
 */
export function webhookConfirmBody(opts = {}) {
  return {
    chainTxHash: opts.chainTxHash || randomTxHash(),
  };
}

/**
 * 生成模拟 Webhook 事件载荷（用于直接打 webhook 端点，若服务暴露）。
 * 兼容 NexusChain 事件格式：event_type + payment_id + status + tx_hash + ts
 */
export function webhookEventPayload(paymentId, opts = {}) {
  return {
    event_type: opts.eventType || "payment.succeeded",
    payment_id: paymentId,
    status: opts.status || "SUCCEEDED",
    amount: opts.amount !== undefined ? opts.amount : rngInt(100, 1_000_000),
    currency: opts.currency || rngChoice(CURRENCIES),
    chain_tx_hash: opts.chainTxHash || randomTxHash(),
    timestamp: Date.now(),
    signature: rngHex(64), // 模拟 webhook 签名
  };
}

// ---------------------------------------------------------------------------
// 工具：解析响应中的 payment id
// ---------------------------------------------------------------------------
/**
 * 从 gateway 响应体提取 id（形如 "pay_xxx"）。
 * @returns {string|null}
 */
export function extractPaymentId(respBody) {
  try {
    const obj = typeof respBody === "string" ? JSON.parse(respBody) : respBody;
    return obj && obj.id ? obj.id : null;
  } catch (e) {
    return null;
  }
}

// ---------------------------------------------------------------------------
// 导出常量
// ---------------------------------------------------------------------------
export const CONSTANTS = {
  CURRENCIES,
  CHAIN_IDS,
  DEFAULT_MERCHANT_ID,
  DEFAULT_NOTIFY_URL,
  DEFAULT_PREFERRED_CONNECTOR,
};