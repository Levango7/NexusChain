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
 * Headers injected on protected (/api/v1/payments/**) endpoints:
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

/** Path prefix that triggers ApiKey + HMAC authentication. */
export const PROTECTED_PATH_PREFIX = "/api/v1/payments";

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
 * Predicate: does a given path require ApiKey + HMAC authentication?
 * Matches the gateway's protected subtree (/api/v1/payments/**).
 */
export function isProtectedPath(path: string): boolean {
  return typeof path === "string" && path.startsWith(PROTECTED_PATH_PREFIX);
}
