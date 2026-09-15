import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter } from "react-router-dom";
import "../../i18n";

/**
 * OrchestrationDashboard 测试。
 *
 * 2026-09-16 审查背景：本页此前零测试，以下问题逃逸 ——
 *   · 请求带 `merchantId=1`（服务端显式忽略，属误导性冗余）
 *   · 每轮轮询先 setLoading(true) 导致整页闪白
 *   · 缺少竞态守卫
 *   · tab 名 / 元信息 / aria-label 硬编码英文
 *   · 全仓无任何入口指向 /orchestration
 */

const authState = vi.hoisted(() => ({
  apiKey: "",
  apiSecret: "",
  isAuthenticated: false,
}));

vi.mock("../../auth/useAuth", () => ({
  useAuth: () => ({
    apiKey: authState.apiKey,
    apiSecret: authState.apiSecret,
    isAuthenticated: authState.isAuthenticated,
    setCredentials: vi.fn(),
    clearCredentials: vi.fn(),
  }),
}));

const requestMock = vi.hoisted(() => vi.fn());

vi.mock("../../api/client", async () => {
  const fx = await import("../fixtures/backend");
  return {
    ApiError: class ApiError extends Error {
      status = 0;
    },
    authenticatedRequest: requestMock,
    api: {},
    AUTH_HEADERS: {},
    isProtectedPath: () => false,
    __fixtures: fx,
  };
});

afterEach(() => {
  cleanup();
  requestMock.mockReset();
});

beforeEach(() => {
  authState.apiKey = "";
  authState.apiSecret = "";
  authState.isAuthenticated = false;
});

describe("OrchestrationDashboard", () => {
  it("未认证时应该显示认证提示与前往设置的链接", async () => {
    const Dashboard = (await import("../../pages/OrchestrationDashboard")).default;
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText(/需要商户认证/)).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: /前往设置/ })).toHaveAttribute("href", "/settings");
    // 未认证不应发起请求
    expect(requestMock).not.toHaveBeenCalled();
  });

  it("认证后应该按 tab 语义渲染三个页签", async () => {
    authState.apiKey = "k".repeat(8);
    authState.apiSecret = "s".repeat(16);
    authState.isAuthenticated = true;

    const { RPC_BLOCK } = await import("../fixtures/backend");
    void RPC_BLOCK;
    const fx = await import("../fixtures/backend");
    requestMock.mockImplementation(async (path: string) => {
      if (path.startsWith("/api/v1/payments?")) return fx.ORCH_PAYMENT_LIST;
      if (path.includes("/connectors")) return [fx.ORCH_CONNECTOR];
      if (path.includes("/routing-rules")) return [fx.ORCH_RULE];
      throw new Error("unexpected path " + path);
    });

    const Dashboard = (await import("../../pages/OrchestrationDashboard")).default;
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );

    const tablist = await screen.findByRole("tablist");
    expect(tablist).toBeInTheDocument();
    const tabs = screen.getAllByRole("tab");
    expect(tabs).toHaveLength(3);
    // 页签文案应为 i18n 中文（此前硬编码英文 Payments/Connectors/Rules）
    expect(tabs.map((t) => t.textContent)).toEqual(["支付", "连接器", "规则"]);
    expect(tabs[0]).toHaveAttribute("aria-selected", "true");
  });

  it("请求路径不应包含服务端已忽略的 merchantId 参数", async () => {
    authState.apiKey = "k".repeat(8);
    authState.apiSecret = "s".repeat(16);
    authState.isAuthenticated = true;

    const fx = await import("../fixtures/backend");
    requestMock.mockImplementation(async (path: string) => {
      if (path.startsWith("/api/v1/payments?")) return fx.ORCH_PAYMENT_LIST;
      if (path.includes("/connectors")) return [fx.ORCH_CONNECTOR];
      if (path.includes("/routing-rules")) return [fx.ORCH_RULE];
      throw new Error("unexpected path " + path);
    });

    const Dashboard = (await import("../../pages/OrchestrationDashboard")).default;
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );

    await waitFor(() => expect(requestMock).toHaveBeenCalled());
    const paths = requestMock.mock.calls.map((c) => String(c[0]));
    expect(paths.some((p) => p.includes("merchantId"))).toBe(false);
  });

  it("支付列表应该渲染后端返回的 snake_case 字段", async () => {
    authState.apiKey = "k".repeat(8);
    authState.apiSecret = "s".repeat(16);
    authState.isAuthenticated = true;

    const fx = await import("../fixtures/backend");
    requestMock.mockImplementation(async (path: string) => {
      if (path.startsWith("/api/v1/payments?")) return fx.ORCH_PAYMENT_LIST;
      if (path.includes("/connectors")) return [fx.ORCH_CONNECTOR];
      if (path.includes("/routing-rules")) return [fx.ORCH_RULE];
      throw new Error("unexpected path " + path);
    });

    const Dashboard = (await import("../../pages/OrchestrationDashboard")).default;
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByText(/pay_abc123/)).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).toContain("SUCCEEDED");
    expect(text).toContain("100 NEX");
    expect(text, "不应渲染出字面量 undefined").not.toContain("undefined");
  });

  it("应该提供返回首页的入口", async () => {
    const Dashboard = (await import("../../pages/OrchestrationDashboard")).default;
    render(
      <MemoryRouter>
        <Dashboard />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByRole("link", { name: /返回/ })).toHaveAttribute("href", "/");
    });
  });
});
