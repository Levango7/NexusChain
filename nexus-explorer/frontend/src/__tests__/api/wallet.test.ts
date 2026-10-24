import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import {
  DEFAULT_WALLET_BASE,
  WalletApiError,
  isWalletConfigured,
  loadWalletConnection,
  saveWalletConnection,
  walletRequest,
  listPendingWithdrawals,
  getCustodyBalance,
  checkWhitelist,
  approveWithdrawal,
  rejectWithdrawal,
  executeWithdrawal,
  type WalletConnection,
} from "../../api/wallet";

/**
 * wallet-service 客户端单测（2026-10-24 新增）。
 *
 * 背景：`api/wallet.ts` 是本仓**第二套鉴权**（wallet-service 用 JWT Bearer，
 * 与网关的四路 HMAC 不相通）的第一个前端通道。此前它零测试，而它承担的
 * 三件事都不能错：
 *   1. **fail-closed** —— 没有 JWT 时必须抛 401，而不是静默发一个无鉴权请求；
 *   2. **路径与参数名** —— wallet-service 的决策端点用 `@RequestParam`（query），
 *      不是 JSON body；拼错就是运行时 400，且没有任何编译期保护；
 *   3. **错误归一** —— 401（令牌失效）与 403（角色不足）必须可区分，
 *      页面据此给出「重新配置」还是「找管理员」两种不同指引。
 */

const CONN: WalletConnection = { baseUrl: "http://wallet.test:8083", jwt: "jwt-abc" };

/** 构造一个最小 fetch Response 桩。 */
function res(
  status: number,
  body: string,
  init: { statusText?: string; json?: boolean } = {},
): Response {
  const { statusText = "", json = false } = init;
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText,
    json: async () => {
      if (!json) throw new SyntaxError("Unexpected token");
      return JSON.parse(body);
    },
    text: async () => body,
  } as unknown as Response;
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  window.sessionStorage.clear();
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe("连接配置的存取", () => {
  it("未配置时回落到默认地址、JWT 为空", () => {
    const c = loadWalletConnection();
    expect(c.baseUrl).toBe(DEFAULT_WALLET_BASE);
    expect(c.jwt).toBe("");
  });

  it("保存后可原样读回，且 baseUrl 去掉首尾空白", () => {
    saveWalletConnection({ baseUrl: "  http://wallet.test:8083  ", jwt: "  tok  " });
    const c = loadWalletConnection();
    expect(c.baseUrl).toBe("http://wallet.test:8083");
    expect(c.jwt).toBe("tok");
  });

  it("JWT 传空串即清除（断开连接的语义）", () => {
    saveWalletConnection({ baseUrl: "http://wallet.test:8083", jwt: "tok" });
    saveWalletConnection({ baseUrl: "http://wallet.test:8083", jwt: "" });
    expect(window.sessionStorage.getItem("nexus.wallet.jwt")).toBeNull();
    expect(loadWalletConnection().jwt).toBe("");
  });

  it("isWalletConfigured：只有 JWT 才算已配置，纯空白不算", () => {
    expect(isWalletConfigured({ baseUrl: "http://x", jwt: "" })).toBe(false);
    expect(isWalletConfigured({ baseUrl: "http://x", jwt: "   " })).toBe(false);
    expect(isWalletConfigured({ baseUrl: "http://x", jwt: "t" })).toBe(true);
  });
});

describe("walletRequest 的鉴权与错误归一", () => {
  it("没有 JWT 时 fail-closed：抛 401 且不发出任何请求", async () => {
    await expect(
      walletRequest({ baseUrl: "http://wallet.test:8083", jwt: "" }, "/x"),
    ).rejects.toMatchObject({ name: "WalletApiError", status: 401 });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("JWT 只有空白时同样 fail-closed", async () => {
    await expect(walletRequest({ ...CONN, jwt: "   " }, "/x")).rejects.toMatchObject({
      status: 401,
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("成功时带 Bearer 头并解析 JSON", async () => {
    fetchMock.mockResolvedValue(res(200, '{"ok":true}'));
    const out = await walletRequest<{ ok: boolean }>(CONN, "/api/v1/wallet/custody/balance");
    expect(out).toEqual({ ok: true });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://wallet.test:8083/api/v1/wallet/custody/balance");
    expect(init.method).toBe("GET");
    expect(init.headers.Authorization).toBe("Bearer jwt-abc");
  });

  it("baseUrl 末尾斜杠被剥掉，避免出现双斜杠", async () => {
    fetchMock.mockResolvedValue(res(200, "{}"));
    await walletRequest({ baseUrl: "http://wallet.test:8083///", jwt: "t" }, "/p");
    expect(fetchMock.mock.calls[0][0]).toBe("http://wallet.test:8083/p");
  });

  it("401 原样透出状态，页面据此提示重新配置", async () => {
    fetchMock.mockResolvedValue(res(401, '{"message":"bad token"}', { json: true }));
    await expect(walletRequest(CONN, "/x")).rejects.toMatchObject({
      status: 401,
      message: "bad token",
    });
  });

  it("403 与 401 必须可区分（角色不足 ≠ 令牌失效）", async () => {
    fetchMock.mockResolvedValue(res(403, '{"message":"Access is denied"}', { json: true }));
    const err = await walletRequest(CONN, "/x").catch((e: unknown) => e);
    expect(err).toBeInstanceOf(WalletApiError);
    expect((err as WalletApiError).status).toBe(403);
  });

  it("非 2xx 且响应体不是 JSON 时保留默认文案", async () => {
    fetchMock.mockResolvedValue(res(500, "<html>oops</html>", { statusText: "Server Error" }));
    const err = (await walletRequest(CONN, "/x").catch((e: unknown) => e)) as WalletApiError;
    expect(err.status).toBe(500);
    expect(err.message).toContain("500");
  });

  it("204 与空响应体都返回 undefined（不炸在 JSON.parse 上）", async () => {
    fetchMock.mockResolvedValueOnce(res(204, ""));
    await expect(walletRequest(CONN, "/x")).resolves.toBeUndefined();
    fetchMock.mockResolvedValueOnce(res(200, ""));
    await expect(walletRequest(CONN, "/x")).resolves.toBeUndefined();
  });
});

describe("端点封装的路径与参数", () => {
  beforeEach(() => {
    fetchMock.mockResolvedValue(res(200, "[]"));
  });

  it("listPendingWithdrawals 走 /withdrawal/pending", async () => {
    await listPendingWithdrawals(CONN);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://wallet.test:8083/api/v1/wallet/withdrawal/pending",
    );
  });

  it("getCustodyBalance 走 /custody/balance", async () => {
    await getCustodyBalance(CONN);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://wallet.test:8083/api/v1/wallet/custody/balance",
    );
  });

  it("checkWhitelist 的地址走 query 且被编码", async () => {
    await checkWhitelist(CONN, "0xabc/def ghi");
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://wallet.test:8083/api/v1/wallet/whitelist/check?address=0xabc%2Fdef%20ghi",
    );
  });

  it("approve 是 POST 且 approvalId 走 query（wallet-service 用 @RequestParam）", async () => {
    await approveWithdrawal(CONN, "req-1");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://wallet.test:8083/api/v1/wallet/withdrawal/approve?approvalId=req-1");
    expect(init.method).toBe("POST");
  });

  it("reject 同时带 approvalId 与 reason，且 reason 被编码", async () => {
    const reason = "疑似欺诈 & 可疑";
    await rejectWithdrawal(CONN, "req-1", reason);
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe(
      "http://wallet.test:8083/api/v1/wallet/withdrawal/reject" +
        `?approvalId=req-1&reason=${encodeURIComponent(reason)}`,
    );
    // 编码必须真的发生：裸 & 会把 reason 截断成两个参数
    expect(url).toContain("%26");
    expect(url.split("&").length).toBe(2);
    expect(init.method).toBe("POST");
  });

  it("execute 是 POST 且走 /withdrawal/execute", async () => {
    await executeWithdrawal(CONN, "req-9");
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe("http://wallet.test:8083/api/v1/wallet/withdrawal/execute?approvalId=req-9");
    expect(init.method).toBe("POST");
  });
});
