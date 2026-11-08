/**
 * Request signing utilities for the NexusChain gateway.
 *
 * Mirrors the HMAC-SHA256 scheme enforced server-side by
 * {@code org.nexus.gateway.security.RequestSignatureInterceptor}.
 *
 * Two protocol versions exist:
 *   v1 (legacy, delimiter-free concatenation):
 *       canonical = timestamp + nonce + method + path + body
 *       signature = lowerHex( HMAC-SHA256(canonical, secret) )
 *     — 字段边界可平移碰撞（短期项 #4 记录的缺陷）。仅兼容期服务端接受。
 *   v2 (length-prefixed canonical, collision-proof):
 *       canonical = "NXC2|" + len(ts)+":"+ts + "|" + len(nonce)+":"+nonce
 *                 + "|" + len(method)+":"+method + "|" + len(path)+":"+path
 *                 + "|" + len(body)+":"+body
 *       signature = "v2:" + lowerHex( HMAC-SHA256(canonical, secret) )
 *     — 长度按 UTF-8 字节计，多语言字节级一致。新客户端默认应产出 v2。
 *
 * Headers injected on protected endpoints (see {@link PROTECTED_PATH_PREFIXES};
 * mirrors the gateway's RequestSignatureInterceptor path registration):
 *   - X-NexusChain-ApiKey
 *   - X-NexusChain-Timestamp   (unix millis, string)
 *   - X-NexusChain-Nonce       (unique per request)
 *   - X-NexusChain-Signature   ("v2:" + hex lowercase)
 *
 * The signing is performed with the Web Crypto API (crypto.subtle), which is
 * asynchronous — every signer here returns a Promise.
 */

export const AUTH_HEADERS = {
  API_KEY: "X-NexusChain-ApiKey",
  SIGNATURE: "X-NexusChain-Signature",
  TIMESTAMP: "X-NexusChain-Timestamp",
  NONCE: "X-NexusChain-Nonce",
} as const;

/**
 * Path prefixes that trigger ApiKey + HMAC request signing.
 *
 * **必须与网关的拦截器注册逐项一致**：{@code WebConfig.addInterceptors} 中
 * {@code requestSignatureInterceptor.addPathPatterns(...)} 的 5 个前缀 —— 见
 * nexus-gateway/src/main/java/org/nexus/gateway/config/WebConfig.java:135-143：
 *   /api/v1/payments/**  /api/v1/refunds/**  /api/v1/orders/**
 *   /api/v2/payments/**  /api/v2/orders/**
 *
 * 历史缺口：此前这里只有 {@code "/api/v1/payments"} 一个前缀。后端在 2026-10-05
 * （退款/订单确认端点加固）与 v2 资金端点加固时把签名面扩到 5 个前缀，前端未同步，
 * 导致 /api/v1/refunds、/api/v1/orders 与整个 /api/v2/** 的请求不被签名。
 *
 * 新增或调整前缀时：改这里 + 同步 @code __tests__/auth/authV2.test.ts 的清单断言。
 */
export const PROTECTED_PATH_PREFIXES = [
  "/api/v1/payments",
  "/api/v1/refunds",
  "/api/v1/orders",
  "/api/v2/payments",
  "/api/v2/orders",
] as const;

export interface SignRequestParams {
  /** HTTP method (GET, POST, ...). Case-sensitive — match gateway exactly. */
  method: string;
  /** Request URI path (with query string), e.g. /api/v1/payments?limit=20. */
  path: string;
  /** Raw request body string. Empty string for bodyless requests. */
  body?: string;
  /** HMAC-SHA256 signing secret (merchant API secret). */
  secret: string;
}

/**
 * Compute the canonical request string used by the gateway.
 *
 *   canonical = timestamp + nonce + method + path + body   （v1，legacy）
 *
 * Order and empty-string handling must match
 * {@code RequestSignatureInterceptor.computeSignature} byte-for-byte.
 */
export function buildCanonicalString(
  timestamp: string,
  nonce: string,
  method: string,
  path: string,
  body?: string,
): string {
  return (timestamp ?? "") + (nonce ?? "") + (method ?? "") + (path ?? "") + (body ?? "");
}

/**
 * Compute the v2 length-prefixed canonical string (collision-proof).
 * Lengths are UTF-8 byte counts; must match
 * {@code RequestSignatureInterceptor.canonicalV2} byte-for-byte.
 */
export function buildCanonicalStringV2(
  timestamp: string,
  nonce: string,
  method: string,
  path: string,
  body?: string,
): string {
  const field = (v: string | undefined): string => {
    const s = v ?? "";
    return `${new TextEncoder().encode(s).length}:${s}|`;
  };
  return "NXC2|" + field(timestamp) + field(nonce) + field(method) + field(path) + field(body);
}

/**
 * Encode an ArrayBuffer to a lowercase hex string.
 * Equivalent to Java's `String.format("%02x", b)` loop.
 */
function bufferToHex(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let hex = "";
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, "0");
  }
  return hex;
}

/**
 * Compute the HMAC-SHA256 signature (hex lowercase) for a request.
 *
 * Uses the Web Crypto API so the secret never materialises in user-space
 * crypto code. Returns a Promise because `crypto.subtle` is async.
 */
export async function signRequest(params: SignRequestParams): Promise<string> {
  const { method, path, body, secret } = params;
  if (!secret) {
    throw new Error("signRequest: secret is empty");
  }

  const timestamp = generateTimestamp();
  const nonce = generateNonce();
  const canonical = buildCanonicalStringV2(timestamp, nonce, method, path, body);

  // Import the secret as an HMAC key once per call. Web Crypto re-derives
  // the key schedule internally; for high-volume callers a cached CryptoKey
  // could be introduced, but correctness-first here.
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signatureBuf = await crypto.subtle.sign("HMAC", key, encoder.encode(canonical));
  const signature = bufferToHex(signatureBuf);

  return "v2:" + signature;
}

/** Current time as a unix-millis string (matches gateway's Long.parseLong). */
export function generateTimestamp(): string {
  return Date.now().toString();
}

/**
 * Generate a per-request nonce. Combines high-resolution time with strong
 * randomness to guarantee uniqueness within the gateway's 5-minute replay
 * window even under tight call loops.
 */
export function generateNonce(): string {
  const rand = crypto.getRandomValues(new Uint8Array(16));
  let hex = "";
  for (let i = 0; i < rand.length; i++) {
    hex += rand[i].toString(16).padStart(2, "0");
  }
  return `${Date.now().toString(16)}-${hex}`;
}

export interface BuildAuthHeadersParams {
  apiKey: string;
  apiSecret: string;
  method: string;
  path: string;
  body?: string;
}

/**
 * Build the full set of NexusChain authentication headers for a protected
 * request. Returns a fresh header record suitable for spreading into fetch's
 * `headers` option.
 */
export async function buildAuthHeaders(
  params: BuildAuthHeadersParams,
): Promise<Record<string, string>> {
  const { apiKey, apiSecret, method, path, body } = params;
  if (!apiKey || !apiSecret) {
    throw new Error("buildAuthHeaders: apiKey and apiSecret are required");
  }

  const timestamp = generateTimestamp();
  const nonce = generateNonce();
  const canonical = buildCanonicalStringV2(timestamp, nonce, method, path, body);

  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(apiSecret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signatureBuf = await crypto.subtle.sign("HMAC", key, encoder.encode(canonical));
  const signature = "v2:" + bufferToHex(signatureBuf);

  return {
    [AUTH_HEADERS.API_KEY]: apiKey,
    [AUTH_HEADERS.TIMESTAMP]: timestamp,
    [AUTH_HEADERS.NONCE]: nonce,
    [AUTH_HEADERS.SIGNATURE]: signature,
  };
}

/**
 * Predicate: does a given path require ApiKey + HMAC request signing?
 * Matches the gateway's protected subtrees ({@link PROTECTED_PATH_PREFIXES}).
 *
 * 两处语义对齐网关：
 *   - **段边界**：Spring 的 {@code /x/**} 命中 {@code /x} 与其子路径，但不命中
 *     {@code /xfoo}。裸 {@code startsWith} 会把 {@code /api/v1/payments-other}
 *     误判为受保护，故此处按段边界判定。
 *   - **query string**：网关用 {@code request.getRequestURI()} 取路径（不含 query），
 *     故这里先剥离 {@code ?} 之后的部分再比较。
 */
export function isProtectedPath(path: string): boolean {
  if (typeof path !== "string") {
    return false;
  }
  const pathOnly = path.split("?")[0] ?? path;
  return PROTECTED_PATH_PREFIXES.some(
    (prefix) => pathOnly === prefix || pathOnly.startsWith(`${prefix}/`),
  );
}
