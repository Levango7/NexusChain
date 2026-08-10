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
 */
async function request<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new ApiError(res.status, `Request failed: ${res.status} ${res.statusText}`);
  }
  return res.json();
}

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
  getBlocks: (limit = 20) => request<BlockInfo[]>(`/api/blocks?limit=${limit}`),
  getBlock: (height: number) => request<BlockInfo>(`/api/blocks/${height}`),

  // Transactions
  getTransactions: (limit = 20) => request<TransactionInfo[]>(`/api/tx?limit=${limit}`),
  getTransaction: (hash: string) => request<TransactionInfo>(`/api/tx/${hash}`),

  // Account
  getAccount: (address: string) => request<AccountInfo>(`/api/address/${address}`),

  // Chain status
  getStatus: () => request<ChainStatus>(`/api/node/status`),
};

export { AUTH_HEADERS, isProtectedPath };
