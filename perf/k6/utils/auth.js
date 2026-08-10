/**
 * NexusChain k6 认证工具 — API Key + HMAC-SHA256 请求签名
 * ============================================================================
 *
 * 对应服务端：
 *   - nexus-gateway/.../ApiKeyInterceptor.java
 *       头：X-NexusChain-ApiKey
 *   - nexus-gateway/.../RequestSignatureInterceptor.java
 *       头：X-NexusChain-Timestamp / X-NexusChain-Nonce / X-NexusChain-Signature
 *       签名载荷：timestamp + nonce + method + path + body
 *       算法：HMAC-SHA256，输出 hex(lowercase)
 *       时间窗：±5min；Nonce 不可重放
 *
 * k6 内置 crypto/hmac 模块（k6 ≥ 0.43）；如运行旧版 k6，可改用 k6 crypto 的
 * hmac 简化 API。本实现兼容 k6 0.43+ 的 `hmac("sha256", key, msg, "hex")`。
 *
 * 用法：
 *   import { buildAuthHeaders } from "./utils/auth.js";
 *   const headers = buildAuthHeaders("POST", "/api/v1/payments", bodyJson);
 */

import http from "k6/http";
import { fail } from "k6";

// ---------------------------------------------------------------------------
// 配置（来自 k6 -e 环境变量；缺失时给出明确失败，避免静默使用空密钥）
// ---------------------------------------------------------------------------
const API_KEY = __ENV.API_KEY || "";
const SIGNING_SECRET = __ENV.SIGNING_SECRET || "";

// 头名称（与服务端 RequestSignatureInterceptor / ApiKeyInterceptor 严格一致）
const HEADER_API_KEY = "X-NexusChain-ApiKey";
const HEADER_TIMESTAMP = "X-NexusChain-Timestamp";
const HEADER_NONCE = "X-NexusChain-Nonce";
const HEADER_SIGNATURE = "X-NexusChain-Signature";

/**
 * 断言凭据已注入；缺失则一次性 fail，避免每个 VU 反复打日志。
 * 在 setup() 中调用一次即可。
 */
export function assertCredentials() {
  if (!API_KEY) {
    fail("缺少 API_KEY 环境变量。请用 -e API_KEY=xxx 注入商户 API Key。");
  }
  if (!SIGNING_SECRET) {
    fail("缺少 SIGNING_SECRET 环境变量。请用 -e SIGNING_SECRET=xxx 注入请求签名密钥。");
  }
}

/**
 * 生成全局唯一 Nonce：时间戳 + VU id + 迭代序号 + 随机后缀。
 * k6 exec.vuIterationsInTest 在 scenario 内可用；不可用时退化为 random。
 */
function genNonce() {
  const ts = Date.now().toString(36);
  const rand = Math.random().toString(36).slice(2, 10);
  // __VU / __ITER 是 k6 全局变量
  const vu = (typeof __VU !== "undefined" ? __VU : 0).toString(36);
  const iter = (typeof __ITER !== "undefined" ? __ITER : 0).toString(36);
  return `k6-${vu}-${iter}-${ts}-${rand}`;
}

/**
 * 计算 HMAC-SHA256，返回 lowercase hex。
 * k6 ≥ 0.43 提供 hmac(algo, key, msg, outputEncoding)。
 * 兼容旧版：若 hmac 不可用，回退到 crypto.createHMAC（k6 早期 API）。
 */
function hmacSha256Hex(key, message) {
  // 优先使用 k6 标准 hmac 函数
  if (typeof hmac === "function") {
    return hmac("sha256", key, message, "hex");
  }
  // 回退：k6 早期 crypto 模块
  // eslint-disable-next-line no-undef
  if (typeof crypto !== "undefined" && crypto.createHMAC) {
    // eslint-disable-next-line no-undef
    const h = crypto.createHMAC("sha256", key);
    h.update(message);
    return h.digest("hex");
  }
  fail("当前 k6 版本不支持 hmac/crypto；请升级到 k6 ≥ 0.43。");
}

/**
 * 构造完整认证头（API Key + HMAC 签名）。
 *
 * @param {string} method  HTTP 方法大写：GET/POST/PUT/DELETE
 * @param {string} path    请求路径（含 query 也允许，但签名仅用 path 部分；
 *                          服务端 getRequestURI() 不含 querystring，故此处
 *                          应传纯 path）
 * @param {string} body    请求体字符串（GET 传 ""）
 * @returns {object}       可直接传给 http.request 的 headers 对象
 */
export function buildAuthHeaders(method, path, body) {
  if (!API_KEY || !SIGNING_SECRET) {
    // 运行期兜底断言，避免静默 401
    assertCredentials();
  }

  const timestamp = Date.now().toString();
  const nonce = genNonce();
  const safeBody = body || "";
  const payload = timestamp + nonce + method + path + safeBody;
  const signature = hmacSha256Hex(SIGNING_SECRET, payload);

  return {
    [HEADER_API_KEY]: API_KEY,
    [HEADER_TIMESTAMP]: timestamp,
    [HEADER_NONCE]: nonce,
    [HEADER_SIGNATURE]: signature,
    "Content-Type": "application/json",
    Accept: "application/json",
  };
}

/**
 * 仅 API Key（无签名）—— 用于不需要 HMAC 的端点（如 connectors 列表）。
 */
export function apiKeyOnlyHeaders() {
  if (!API_KEY) assertCredentials();
  return {
    [HEADER_API_KEY]: API_KEY,
    Accept: "application/json",
  };
}

/**
 * 暴露常量供脚本引用（如自定义日志）。
 */
export const HEADER_NAMES = {
  API_KEY: HEADER_API_KEY,
  TIMESTAMP: HEADER_TIMESTAMP,
  NONCE: HEADER_NONCE,
  SIGNATURE: HEADER_SIGNATURE,
};