import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { render, screen, waitFor, cleanup, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import "../../i18n";

/**
 * FundDashboardPage 测试（2026-10-25）。
 *
 * 重点盯四类容易静默出错的地方：
 *   1. **未认证不发请求** —— 没有凭证还打网关，只会换来一个 401 横幅，
 *      不如直接引导去设置；
 *   2. **空数据要显式空态** —— 资金页最怕"看起来正常但其实没数据"；
 *   3. **异常态必须显眼** —— 有 alert 却渲染成正常，比不显示更危险；
 *   4. **不渲染 undefined / null 字面量** —— 金额缺字段时显示占位符而不是 `undefined`。
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

vi.mock("../../api/client", () => ({
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.name = "ApiError";
      this.status = status;
    }
  },
  authenticatedRequest: requestMock,
  api: {},
  AUTH_HEADERS: {},
  isProtectedPath: () => false,
}));

import { ApiError } from "../../api/client";

const FULL = {
  merchantId: 200,
  fundOverview: {
    balance: 12345.67,
    frozen: 100,
    reserve: 5000,
    totalAssets: 17445.67,
    balanceStatus: "ACTIVE",
    alertFlag: null,
  },
  transactionTrend: [
    {
      date: "2026-10-24",
      totalCredit: 1000,
      totalDebit: 200,
      netFlow: 800,
      creditCount: 3,
      debitCount: 1,
    },
    {
      date: "2026-10-23",
      totalCredit: 500,
      totalDebit: 500,
      netFlow: 0,
      creditCount: 2,
      debitCount: 2,
    },
  ],
  recentTransactions: [
    {
      txNo: "TX-1",
      operationType: "PAYMENT_IN",
      direction: "CREDIT",
      amount: 1000,
      balanceBefore: 11345.67,
      balanceAfter: 12345.67,
      reference: "ORD-1",
      createdAt: "2026-10-24T10:00:00",
    },
  ],
  anomalyMonitor: { hasAlert: false, alertLevel: null, anomalyCount: 0, anomalies: [] },
  generatedAt: "2026-10-24T12:00:00",
};

const EMPTY = {
  merchantId: 200,
  fundOverview: {
    balance: null,
    frozen: null,
    reserve: null,
    totalAssets: null,
    balanceStatus: "NOT_CREATED",
    alertFlag: null,
  },
  transactionTrend: [],
  recentTransactions: [],
  anomalyMonitor: { hasAlert: false, alertLevel: null, anomalyCount: 0, anomalies: [] },
  generatedAt: "2026-10-24T12:00:00",
};

/**
 * 渲染并把首轮异步取数在 act 内落定。
 *
 *  在 useEffect 里被调用，其 setData / setError / setLoading 发生在
 * render() 返回之后的微任务里；不包 act 会给每一次更新打一条
 * "not wrapped in act" 警告（基线为 0，不应由本文件引入噪音）。
 */
async function renderPage() {
  const Page = (await import("../../pages/FundDashboardPage")).default;
  let utils!: ReturnType<typeof render>;
  await act(async () => {
    utils = render(
      <MemoryRouter>
        <Page />
      </MemoryRouter>,
    );
  });
  return utils;
}

beforeEach(() => {
  authState.apiKey = "k";
  authState.apiSecret = "s";
  authState.isAuthenticated = true;
  requestMock.mockReset();
});

afterEach(() => {
  cleanup();
});

describe("未配置凭证", () => {
  it("不发请求，直接引导去设置", async () => {
    authState.isAuthenticated = false;
    await renderPage();

    expect(screen.getByText(/需要先在设置里配置/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "前往设置" })).toBeInTheDocument();
    expect(requestMock).not.toHaveBeenCalled();
  });
});

describe("数据渲染", () => {
  it("概览四卡 + 趋势 + 明细都渲染", async () => {
    requestMock.mockResolvedValue(FULL);
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText("资金概览")).toBeInTheDocument();
    });
    expect(screen.getByText("可用余额")).toBeInTheDocument();
    expect(screen.getByText("总资产")).toBeInTheDocument();
    // 金额按 locale 千分位
    // 概览卡与明细的 balanceAfter 都会出现这个数，故用 getAllByText
    expect(screen.getAllByText("12,345.67").length).toBeGreaterThan(0);
    // 趋势两行
    expect(screen.getByText("2026-10-24")).toBeInTheDocument();
    expect(screen.getByText("2026-10-23")).toBeInTheDocument();
    // 明细一行：方向被本地化
    expect(screen.getByText("PAYMENT_IN")).toBeInTheDocument();
    // "收入" 同时是趋势表头与方向单元格，故用 getAllByText
    expect(screen.getAllByText("收入").length).toBeGreaterThan(0);
    // 账户状态
    expect(screen.getByText("ACTIVE")).toBeInTheDocument();
  });

  it("请求路径不带 merchantId（服务端按 API Key 推导）", async () => {
    requestMock.mockResolvedValue(FULL);
    await renderPage();

    await waitFor(() => {
      expect(requestMock).toHaveBeenCalled();
    });
    const [path, opts] = requestMock.mock.calls[0];
    expect(path).toBe("/api/v1/fund-dashboard");
    expect(path).not.toContain("merchantId");
    expect(opts.apiKey).toBe("k");
    expect(opts.apiSecret).toBe("s");
  });

  it("空数据给显式空态，不渲染 undefined / null 字面量", async () => {
    requestMock.mockResolvedValue(EMPTY);
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText("暂无流水数据")).toBeInTheDocument();
    });
    expect(screen.getByText("暂无流水记录")).toBeInTheDocument();
    expect(screen.getByText("暂无异常流水")).toBeInTheDocument();
    expect(screen.getByText("未开户")).toBeInTheDocument();

    const text = document.body.textContent ?? "";
    expect(text).not.toContain("undefined");
    expect(text).not.toContain("null");
  });

  it("金额缺字段时显示占位符而不是 Invalid Date / NaN", async () => {
    requestMock.mockResolvedValue(EMPTY);
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText("资金概览")).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).not.toContain("NaN");
    expect(text).not.toContain("Invalid Date");
    expect(text).toContain("—");
  });

  it("显示数据生成时间（本页不自动刷新，新鲜度必须可见）", async () => {
    requestMock.mockResolvedValue(FULL);
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText(/数据生成于/)).toBeInTheDocument();
    });
  });
});

describe("异常态", () => {
  it("hasAlert 时给危险徽标与人工核实提示", async () => {
    requestMock.mockResolvedValue({
      ...FULL,
      anomalyMonitor: {
        hasAlert: true,
        alertLevel: "NEGATIVE_BALANCE",
        anomalyCount: 1,
        anomalies: [
          {
            txNo: "TX-BAD",
            operationType: "REFUND",
            amount: 500,
            balanceAfter: -20,
            createdAt: "2026-10-24T11:00:00",
          },
        ],
      },
    });
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText("发现异常，请人工核实")).toBeInTheDocument();
    });
    expect(screen.getByText("NEGATIVE_BALANCE")).toBeInTheDocument();
    expect(screen.getByText("REFUND")).toBeInTheDocument();
  });

  it("无异常时显示正常态", async () => {
    requestMock.mockResolvedValue(FULL);
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText("未发现异常")).toBeInTheDocument();
    });
  });
});

describe("错误处理", () => {
  it("401 提示重新填写凭证", async () => {
    requestMock.mockRejectedValue(new ApiError(401, "bad key"));
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText(/API 凭证无效或已失效/)).toBeInTheDocument();
    });
  });

  it("非 401 的 ApiError 带状态码", async () => {
    requestMock.mockRejectedValue(new ApiError(503, "unavailable"));
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText(/加载失败 \(503\)/)).toBeInTheDocument();
    });
  });

  it("非 ApiError 异常也归一为可读文案", async () => {
    requestMock.mockRejectedValue(new Error("boom"));
    await renderPage();

    await waitFor(() => {
      expect(screen.getByText("boom")).toBeInTheDocument();
    });
  });
});

describe("刷新", () => {
  it("点刷新会再请求一次", async () => {
    requestMock.mockResolvedValue(FULL);
    await renderPage();

    await waitFor(() => {
      expect(requestMock).toHaveBeenCalledTimes(1);
    });

    await userEvent.setup().click(screen.getByRole("button", { name: "刷新" }));

    await waitFor(() => {
      expect(requestMock).toHaveBeenCalledTimes(2);
    });
  });
});
