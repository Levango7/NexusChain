import type { BlockInfo, TransactionInfo, AccountInfo, ChainStatus } from "../types";
import {
  AUTH_HEADERS,
  buildAuthHeaders,
  isProtectedPath,
} from "./auth";

const API_BASE = import.meta.env.VITE_API_BASE ?? "http://localhost:3000";
const GATEWAY_BASE = import.meta.env.VITE_GATEWAY_BASE ?? "http://localhost:8080";

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = "ApiError";
  }
}

/**
 * Lightweight BFF request helper. Used for the explorer's own backend
 * (/api/blocks, /api/tx, ...) which does NOT require ApiKey/HMAC auth.
 *
 * 超时兜底（质量审查 2026-09-10）：裸 fetch 无 timeout——后端挂起时首页
 * 永远 Loading（且 10s 轮询会堆积请求）。8s 超时对局域/公网 BFF 均宽裕。
 *
 * 运行时字段校验（2026-09-16 审查 P0）：`request<T>()` 本质是**类型断言**，
 * 无运行时校验 —— 后端字段名一变，类型系统不会报错，页面会拿到 `undefined`
 * 并在渲染期崩溃（实例：`status.height.toLocaleString()` 导致首页整页被
 * ErrorBoundary 替换）。故对每个端点声明必需字段，缺失即抛出可诊断的错误，
 * 把「静默渲染 undefined」提前为「明确的契约错误」。
 */
const REQUEST_TIMEOUT_MS = 8_000;

/**
 * 断言响应对象包含指定字段。
 *
 * @param value   响应体（数组时逐元素检查）
 * @param required 必需字段名
 * @param label   错误信息中显示的端点标识
 */
function assertShape<T>(value: unknown, required: readonly string[], label: string): T {
  const items: unknown[] = Array.isArray(value) ? value : [value];
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item === null || typeof item !== "object") {
      throw new ApiError(0, `${label}: 响应第 ${i} 项不是对象`);
    }
    const missing = required.filter((k) => !(k in (item as Record<string, unknown>)));
    if (missing.length > 0) {
      throw new ApiError(
        0,
        `${label}: 响应缺少字段 [${missing.join(", ")}]（后端契约可能已变更）`,
      );
    }
  }
  return value as T;
}

async function request<T>(
  path: string,
  requiredFields?: readonly string[],
): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed: ${res.status} ${res.statusText}`);
  }
  const body: unknown = await res.json();
  return requiredFields ? assertShape<T>(body, requiredFields, path) : (body as T);
}

/** 各端点的必需字段（与后端实际返回对齐，见 types/index.ts 的来源注释）。 */
const REQUIRED_FIELDS = {
  chainStatus: ["chainId", "latestHeight", "latestHash", "peers", "version"],
  block: ["height", "hash", "parentHash", "timestamp", "txCount", "proposer"],
  transaction: ["txHash", "blockHeight", "from", "to", "amount", "status", "timestamp"],
  account: ["address", "balance", "txCount"],
} as const;

export interface AuthenticatedRequestOptions {
  /** HTTP method. Defaults to "GET". */
  method?: string;
  /** JSON-serialisable request body. */
  body?: unknown;
  /** Merchant API key (X-NexusChain-ApiKey). */
  apiKey: string;
  /** Merchant API secret (HMAC signing key). */
  apiSecret: string;
  /**
   * Gateway base URL. Defaults to VITE_GATEWAY_BASE / localhost:8080.
   * Override only when targeting a non-default gateway instance.
   */
  baseUrl?: string;
  /** Extra headers to merge in (e.g. Accept, Content-Type). */
  headers?: Record<string, string>;
}

/**
 * Authenticated request to the gateway's protected endpoints
 * (/api/v1/payments/**). Automatically injects the four NexusChain auth
 * headers (ApiKey, Timestamp, Nonce, Signature) by computing an HMAC-SHA256
 * signature over the canonical (timestamp + nonce + method + path + body)
 * string — exactly matching the server-side RequestSignatureInterceptor.
 *
 * Throws {@link ApiError} on non-2xx, including 401 when credentials are
 * missing/invalid. Callers MUST surface 401 to the user (do not swallow).
 */
export async function authenticatedRequest<T>(
  path: string,
  options: AuthenticatedRequestOptions,
): Promise<T> {
  const {
    method = "GET",
    body,
    apiKey,
    apiSecret,
    baseUrl = GATEWAY_BASE,
    headers: extraHeaders,
  } = options;

  if (!apiKey || !apiSecret) {
    // Fail closed with a 401-shaped error so callers can uniformly detect
    // "needs credentials" via `err.status === 401`.
    throw new ApiError(401, "Missing merchant API credentials");
  }

  // The gateway signs the raw body bytes. We serialise once and reuse the
  // string for both signing and the fetch body to guarantee byte-equality.
  const bodyString =
    body !== undefined && body !== null ? JSON.stringify(body) : "";

  // Sign the path as-is (with query string) — the gateway's
  // RequestSignatureInterceptor uses request.getRequestURI() which excludes
  // the query string, but the gateway team has confirmed that the canonical
  // path for signed requests is the URI without query. To stay safe we sign
  // the path-only portion and let the query travel unsigned, matching the
  // server's getRequestURI() semantics.
  const pathOnly = path.split("?")[0] ?? path;

  const authHeaders = await buildAuthHeaders({
    apiKey,
    apiSecret,
    method,
    path: pathOnly,
    body: bodyString,
  });

  const headers: Record<string, string> = {
    Accept: "application/json",
    ...authHeaders,
    ...(extraHeaders ?? {}),
  };
  if (bodyString) {
    headers["Content-Type"] = "application/json";
  }

  const res = await fetch(`${baseUrl}${path}`, {
    method,
    headers,
    body: bodyString || undefined,
    // 超时兜底（质量审查 2026-09-10）：签名操作经 gateway 转发签名服务，
    // 链节点慢时可到秒级——给 30s（比 BFF request 的 8s 宽，但绝不无限等）
    signal: AbortSignal.timeout(30_000),
  });

  if (!res.ok) {
    // Attempt to extract the gateway's {code, message} envelope for richer
    // diagnostics, but never block the throw on parse failure.
    let message = `Request failed: ${res.status} ${res.statusText}`;
    try {
      const errBody = (await res.json()) as { code?: number; message?: string };
      if (errBody && typeof errBody.message === "string") {
        message = errBody.message;
      }
    } catch {
      /* keep default message */
    }
    throw new ApiError(res.status, message);
  }

  // 204 No Content / empty body → undefined (typed as T by caller).
  if (res.status === 204) {
    return undefined as unknown as T;
  }
  const text = await res.text();
  if (!text) {
    return undefined as unknown as T;
  }
  return JSON.parse(text) as T;
}

export const api = {
  // Blocks
  getBlocks: (limit = 20) =>
    request<BlockInfo[]>(`/api/blocks?limit=${limit}`, REQUIRED_FIELDS.block),
  getBlock: (height: number) =>
    request<BlockInfo>(`/api/blocks/${height}`, REQUIRED_FIELDS.block),

  // Transactions
  getTransactions: (limit = 20) =>
    request<TransactionInfo[]>(`/api/tx?limit=${limit}`, REQUIRED_FIELDS.transaction),
  getTransaction: (hash: string) =>
    request<TransactionInfo>(`/api/tx/${hash}`, REQUIRED_FIELDS.transaction),

  // Account
  getAccount: (address: string) =>
    request<AccountInfo>(`/api/address/${address}`, REQUIRED_FIELDS.account),

  // Chain status
  getStatus: () => request<ChainStatus>(`/api/node/status`, REQUIRED_FIELDS.chainStatus),
};

export { AUTH_HEADERS, isProtectedPath };
