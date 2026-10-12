import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { api } from "../../api/client";
import { CONTRACT_INFO, CROSSCHAIN_TX } from "../fixtures/backend";
import "../../i18n";

/**
 * 合约页 / 跨链页测试（2026-10-12 新增）。
 *
 * 背景：这两条链路此前**完全不存在** —— README 承诺「合约查看 / 跨链交易追踪」，
 * 后端 `GET /api/contracts` 与 `GET /api/crosschain` 一直可用，但前端零消费，
 * 后端能力对用户不可达（routes.test.tsx 的同源教训：路由/端点存在 ≠ 用户可达）。
 *
 * 夹具全部取自 `__tests__/fixtures/backend.ts`（后端真实字段形状），
 * 且刻意**不带 `abi`** —— core 当前不返回该字段，UI 必须显式说明而不是渲染空白。
 */

vi.mock("../../api/client", () => ({
  ApiError: class ApiError extends Error {
    status = 0;
  },
  api: {
    getContracts: vi.fn(),
    getContract: vi.fn(),
    getCrossChain: vi.fn(),
  },
  authenticatedRequest: vi.fn(),
  AUTH_HEADERS: {},
  isProtectedPath: () => false,
}));

const mocked = vi.mocked(api);

beforeEach(() => {
  mocked.getContracts.mockResolvedValue([CONTRACT_INFO]);
  mocked.getContract.mockResolvedValue(CONTRACT_INFO);
  mocked.getCrossChain.mockResolvedValue([CROSSCHAIN_TX]);
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/** 页面不应渲染出字面量 undefined / null，也不应落到错误边界。 */
function expectNoUndefinedLeak() {
  const text = document.body.textContent ?? "";
  expect(text, "页面不应渲染出字面量 undefined").not.toContain("undefined");
  expect(text, "页面不应渲染出字面量 null").not.toContain("null");
  expect(text, "页面不应落到 ErrorBoundary fallback").not.toContain("页面渲染出错");
}

describe("ContractsPage", () => {
  it("应该渲染合约列表并提供 /contracts/:addr 详情入口", async () => {
    const ContractsPage = (await import("../../pages/ContractsPage")).default;
    render(
      <MemoryRouter>
        <ContractsPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText("NEXcontractAddress")).toBeInTheDocument();
    });
    const link = screen.getByRole("link", { name: /查看合约/ });
    expect(link).toHaveAttribute("href", "/contracts/NEXcontractAddress");
    expect(document.body.textContent).toContain("NEXcreatorAddress");
    expectNoUndefinedLeak();
  });

  it("无已部署合约时应展示空态而非空白页", async () => {
    mocked.getContracts.mockResolvedValue([]);
    const ContractsPage = (await import("../../pages/ContractsPage")).default;
    render(
      <MemoryRouter>
        <ContractsPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText("暂无已部署合约")).toBeInTheDocument();
    });
    expectNoUndefinedLeak();
  });

  it("拉取失败时应展示错误且清空列表（不把失败显示成「合约被删」）", async () => {
    mocked.getContracts.mockRejectedValue(new Error("contracts boom"));
    const ContractsPage = (await import("../../pages/ContractsPage")).default;
    render(
      <MemoryRouter>
        <ContractsPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("contracts boom");
    });
    expect(document.body.textContent).not.toContain("NEXcontractAddress");
    expectNoUndefinedLeak();
  });
});

describe("ContractPage", () => {
  const renderDetail = async () => {
    const ContractPage = (await import("../../pages/ContractPage")).default;
    render(
      <MemoryRouter initialEntries={["/contracts/NEXcontractAddress"]}>
        <Routes>
          <Route path="/contracts/:addr" element={<ContractPage />} />
        </Routes>
      </MemoryRouter>,
    );
  };

  it("应该渲染部署者与代码哈希，并在无 ABI 时给出说明", async () => {
    await renderDetail();
    await waitFor(() => {
      expect(screen.getByText("代码哈希")).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).toContain("NEXcreatorAddress");
    expect(text).toContain("节点未提供该合约的 ABI");
    expectNoUndefinedLeak();
  });

  it("合约不存在时应展示 notFound 而非空白", async () => {
    mocked.getContract.mockResolvedValue(null as unknown as typeof CONTRACT_INFO);
    await renderDetail();
    await waitFor(() => {
      expect(screen.getByText("未找到合约")).toBeInTheDocument();
    });
    expectNoUndefinedLeak();
  });

  it("超长 wasmCode 应默认截断，并可展开完整代码", async () => {
    const longCode = "0a".repeat(300);
    mocked.getContract.mockResolvedValue({ ...CONTRACT_INFO, wasmCode: longCode });
    await renderDetail();
    await waitFor(() => {
      expect(screen.getByText(/仅显示前 200 个字符/)).toBeInTheDocument();
    });
    await userEvent.click(screen.getByRole("button", { name: "展开完整代码" }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "收起代码" })).toBeInTheDocument();
    });
    expect(document.body.textContent).toContain(longCode);
    expectNoUndefinedLeak();
  });

  it("节点返回 abi 时应渲染其 JSON 内容", async () => {
    mocked.getContract.mockResolvedValue({
      ...CONTRACT_INFO,
      abi: [{ name: "transfer", type: "function" }],
    });
    await renderDetail();
    await waitFor(() => {
      expect(document.body.textContent).toContain('"name": "transfer"');
    });
    expect(document.body.textContent).not.toContain("节点未提供该合约的 ABI");
    expectNoUndefinedLeak();
  });
});

describe("CrossChainPage", () => {
  it("应该渲染跨链字段、状态标签与金额", async () => {
    const CrossChainPage = (await import("../../pages/CrossChainPage")).default;
    render(
      <MemoryRouter>
        <CrossChainPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText("xchain-0001")).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).toMatch(/nexus\s*→\s*ethereum/);
    expect(text).toContain("已确认");
    expect(text).toContain("50 NEX");
    expectNoUndefinedLeak();
  });

  it("切换状态筛选应以该状态重新拉取（而非本地过滤）", async () => {
    const CrossChainPage = (await import("../../pages/CrossChainPage")).default;
    render(
      <MemoryRouter>
        <CrossChainPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(mocked.getCrossChain).toHaveBeenCalledWith(50, undefined);
    });
    await userEvent.click(screen.getByRole("button", { name: "待确认" }));
    await waitFor(() => {
      const lastCall = mocked.getCrossChain.mock.calls.at(-1);
      expect(lastCall?.[1]).toBe("pending");
    });
    expectNoUndefinedLeak();
  });

  it("未知状态不应崩溃：回落 neutral 标签与原始状态值", async () => {
    // core 侧状态枚举扩展时（前端未同步），旧前端仍须可用 —— 这属于契约演进
    // 的正常路径，不是异常路径，故显式断言回退行为。
    mocked.getCrossChain.mockResolvedValue([
      { ...CROSSCHAIN_TX, status: "relayed" as unknown as "confirmed" },
    ]);
    const CrossChainPage = (await import("../../pages/CrossChainPage")).default;
    render(
      <MemoryRouter>
        <CrossChainPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText("relayed")).toBeInTheDocument();
    });
    expectNoUndefinedLeak();
  });

  it("拉取失败时应展示错误且清空列表", async () => {
    mocked.getCrossChain.mockRejectedValue(new Error("crosschain boom"));
    const CrossChainPage = (await import("../../pages/CrossChainPage")).default;
    render(
      <MemoryRouter>
        <CrossChainPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("crosschain boom");
    });
    expect(document.body.textContent).not.toContain("xchain-0001");
    expectNoUndefinedLeak();
  });

  it("无跨链交易时应展示空态", async () => {
    mocked.getCrossChain.mockResolvedValue([]);
    const CrossChainPage = (await import("../../pages/CrossChainPage")).default;
    render(
      <MemoryRouter>
        <CrossChainPage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText("暂无跨链交易")).toBeInTheDocument();
    });
    expectNoUndefinedLeak();
  });
});
