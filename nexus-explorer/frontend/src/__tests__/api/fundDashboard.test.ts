import { describe, it, expect, vi, beforeEach } from "vitest";

/**
 * `api/fundDashboard.ts` 单测（2026-10-25）。
 *
 * 只钉一件最容易被"顺手改回去"的事：**请求路径不能带 merchantId**。
 *
 * 商户侧身份由 `ApiKeyInterceptor` 从 API Key 推导，客户端不持有自己的 merchantId
 * （全仓无 `/merchants/me`）。改成 `/api/v1/fund-dashboard/${id}` 会要求前端猜一个
 * 自己不该知道的数字——猜错就是跨商户读取（服务端会 403，但页面永远打不开）。
 */

const requestMock = vi.hoisted(() => vi.fn());

vi.mock("../../api/client", () => ({
  authenticatedRequest: requestMock,
  ApiError: class ApiError extends Error {},
  api: {},
  AUTH_HEADERS: {},
  isProtectedPath: () => false,
}));

import { getMyFundDashboard } from "../../api/fundDashboard";

beforeEach(() => {
  requestMock.mockReset();
});

describe("getMyFundDashboard", () => {
  it("调用不带 merchantId 的聚合端点", async () => {
    requestMock.mockResolvedValue({ merchantId: 200 });

    const res = await getMyFundDashboard({ apiKey: "k", apiSecret: "s" });

    expect(res).toEqual({ merchantId: 200 });
    const [path, opts] = requestMock.mock.calls[0];
    expect(path).toBe("/api/v1/fund-dashboard");
    expect(path).not.toContain("merchantId");
    expect(opts).toEqual({ apiKey: "k", apiSecret: "s" });
  });

  it("透传上游错误（不吞 401，页面要据此提示重新配置）", async () => {
    const boom = new Error("unauthorized");
    requestMock.mockRejectedValue(boom);

    await expect(getMyFundDashboard({ apiKey: "k", apiSecret: "s" })).rejects.toBe(boom);
  });
});
