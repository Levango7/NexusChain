/**
 * nexus-wallet-service 客户端（托管钱包 / 提现审批）。
 *
 * <h3>为什么不能复用 {@link ./client.authenticatedRequest}</h3>
 * 仓库里存在<b>两套互不相通的鉴权</b>：
 * <ul>
 *   <li><b>nexus-gateway</b>：四路 HMAC 签名（ApiKey / Timestamp / Nonce / Signature）——
 *       {@code api/client.ts} 的 {@code authenticatedRequest} 走这条；</li>
 *   <li><b>nexus-wallet-service</b>：Spring Security + <b>JWT Bearer</b>
 *       （{@code JwtAuthenticationFilter} / {@code JwtTokenProvider}）——
 *       角色为 {@code OPERATOR} / {@code APPROVER} / {@code ADMIN}。</li>
 * </ul>
 *
 * <p>网关虽然有 {@code FeignJwtRequestInterceptor} 可以自签服务 JWT，但那是
 * {@code subject=nexus-gateway} 的<b>服务身份</b>。若让网关代理审批决策，
 * 网关就会以 APPROVER 身份代所有人批准 —— <b>审批流形同虚设</b>。
 * 因此本模块直连 wallet-service，JWT 由运维提供。</p>
 *
 * <h3>参数约定</h3>
 * wallet-service 的决策端点用的是 {@code @RequestParam}（不是 JSON body），
 * 所以 {@code POST /withdrawal/approve?approvalId=...} 形式，参数走 query string。
 */

const WALLET_BASE_STORAGE_KEY = "nexus.wallet.baseUrl";
const WALLET_JWT_STORAGE_KEY = "nexus.wallet.jwt";

/** 默认 wallet-service 地址（与 docker-compose / 本地开发约定一致）。 */
export const DEFAULT_WALLET_BASE = "http://localhost:8083";

/** 提现状态（与 SDK `WithdrawalRequest.WithdrawalStatus` 的 6 个值一致）。 */
export type WithdrawalStatus =
  | "PENDING"
  | "APPROVED"
  | "REJECTED"
  | "EXECUTING"
  | "EXECUTED"
  | "FAILED";

/** 提现请求 DTO（对齐 `org.nexus.sdk.wallet.WithdrawalRequest`）。 */
export interface WithdrawalRequestDto {
  requestId: string;
  toAddress: string;
  amount: string | number;
  currency: string;
  status: WithdrawalStatus;
  approvers: string[];
  requiredApprovers: number | null;
  approvedCount: number;
  chainTxHash: string | null;
  rejectionReason: string | null;
  createdAt: string | null;
  executedAt: string | null;
}

/** 托管余额（`GET /custody/balance` 返回 `{hot, cold}`）。 */
export interface CustodyBalanceDto {
  hot?: unknown;
  cold?: unknown;
}

/** 白名单检查结果（`GET /whitelist/check`）。 */
export interface WhitelistCheckDto {
  address: string;
  whitelisted: boolean;
  firstTimeDelay: boolean;
}

/** wallet-service 调用错误：保留 HTTP 状态以便区分 401/403/网络。 */
export class WalletApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "WalletApiError";
    this.status = status;
  }
}

/**
 * 连接配置（base URL + JWT）。
 *
 * <p><b>JWT 存 sessionStorage 而非 localStorage</b>：它是 bearer 凭证，
 * 关标签页即失效比长期驻留更安全；且不需要像 `AuthContext` 那样做
 * base64+XOR 混淆（那种混淆只是避免肉眼可见，不是真加密）。</p>
 */
export interface WalletConnection {
  baseUrl: string;
  jwt: string;
}

function safeGet(storage: Storage | null, key: string): string {
  try {
    return storage?.getItem(key) ?? "";
  } catch {
    return "";
  }
}

function safeSet(storage: Storage | null, key: string, value: string): void {
  try {
    if (value) storage?.setItem(key, value);
    else storage?.removeItem(key);
  } catch {
    /* sessionStorage 不可用（隐私模式）——降级为仅内存态，不报错 */
  }
}

/** 读取已保存的连接配置。 */
export function loadWalletConnection(): WalletConnection {
  const session = typeof window === "undefined" ? null : window.sessionStorage;
  return {
    baseUrl: safeGet(session, WALLET_BASE_STORAGE_KEY) || DEFAULT_WALLET_BASE,
    jwt: safeGet(session, WALLET_JWT_STORAGE_KEY),
  };
}

/** 保存连接配置；`jwt` 传空串即清除。 */
export function saveWalletConnection(conn: WalletConnection): void {
  const session = typeof window === "undefined" ? null : window.sessionStorage;
  safeSet(session, WALLET_BASE_STORAGE_KEY, conn.baseUrl.trim());
  safeSet(session, WALLET_JWT_STORAGE_KEY, conn.jwt.trim());
}

/** 是否已具备可用连接（有 JWT 即可尝试，URL 有默认值）。 */
export function isWalletConfigured(conn: WalletConnection): boolean {
  return conn.jwt.trim().length > 0;
}

/**
 * 调 wallet-service。
 *
 * @param conn   连接配置（base URL + JWT）
 * @param path   路径（含 query，如 `/withdrawal/approve?approvalId=x`）
 * @param init   额外 fetch 选项（method 等）
 * @throws {WalletApiError} 非 2xx，含 401（JWT 缺失/失效）与 403（角色不足）
 */
export async function walletRequest<T>(
  conn: WalletConnection,
  path: string,
  init: { method?: string; signal?: AbortSignal } = {},
): Promise<T> {
  const { method = "GET", signal } = init;

  if (!conn.jwt.trim()) {
    // fail-closed：与 gateway 客户端同构，让调用方统一用 status === 401 判断
    throw new WalletApiError(401, "Missing wallet-service JWT");
  }

  const base = conn.baseUrl.replace(/\/+$/, "");
  const res = await fetch(`${base}${path}`, {
    method,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${conn.jwt.trim()}`,
    },
    // 与 gateway 客户端对齐的 30s 兜底；审批动作不应无限等待
    signal: signal ?? AbortSignal.timeout(30_000),
  });

  if (!res.ok) {
    let message = `Request failed: ${res.status} ${res.statusText}`;
    try {
      const body = (await res.json()) as { message?: string };
      if (body && typeof body.message === "string") message = body.message;
    } catch {
      /* 保留默认文案 */
    }
    throw new WalletApiError(res.status, message);
  }

  if (res.status === 204) return undefined as unknown as T;
  const text = await res.text();
  if (!text) return undefined as unknown as T;
  return JSON.parse(text) as T;
}

// === 端点封装（与 wallet-service 的 12 个端点一一对应，此处只用审批相关的） ===

/** 待审批队列（按创建时间倒序）。 */
export function listPendingWithdrawals(
  conn: WalletConnection,
  signal?: AbortSignal,
): Promise<WithdrawalRequestDto[]> {
  return walletRequest<WithdrawalRequestDto[]>(conn, "/api/v1/wallet/withdrawal/pending", {
    signal,
  });
}

/** 托管余额（热/冷钱包）。 */
export function getCustodyBalance(
  conn: WalletConnection,
  signal?: AbortSignal,
): Promise<CustodyBalanceDto> {
  return walletRequest<CustodyBalanceDto>(conn, "/api/v1/wallet/custody/balance", { signal });
}

/** 单个地址的白名单状态。 */
export function checkWhitelist(
  conn: WalletConnection,
  address: string,
  signal?: AbortSignal,
): Promise<WhitelistCheckDto> {
  return walletRequest<WhitelistCheckDto>(
    conn,
    `/api/v1/wallet/whitelist/check?address=${encodeURIComponent(address)}`,
    { signal },
  );
}

/** 批准一笔提现。 */
export function approveWithdrawal(
  conn: WalletConnection,
  approvalId: string,
): Promise<WithdrawalRequestDto> {
  return walletRequest<WithdrawalRequestDto>(
    conn,
    `/api/v1/wallet/withdrawal/approve?approvalId=${encodeURIComponent(approvalId)}`,
    { method: "POST" },
  );
}

/** 拒绝一笔提现（必须给原因）。 */
export function rejectWithdrawal(
  conn: WalletConnection,
  approvalId: string,
  reason: string,
): Promise<WithdrawalRequestDto> {
  return walletRequest<WithdrawalRequestDto>(
    conn,
    `/api/v1/wallet/withdrawal/reject?approvalId=${encodeURIComponent(approvalId)}` +
      `&reason=${encodeURIComponent(reason)}`,
    { method: "POST" },
  );
}

/** 执行一笔已获批的提现（链上广播）。 */
export function executeWithdrawal(
  conn: WalletConnection,
  approvalId: string,
): Promise<WithdrawalRequestDto> {
  return walletRequest<WithdrawalRequestDto>(
    conn,
    `/api/v1/wallet/withdrawal/execute?approvalId=${encodeURIComponent(approvalId)}`,
    { method: "POST" },
  );
}
