import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, waitFor, cleanup } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import "../../i18n";

/**
 * 区块浏览器页面级测试。
 *
 * 2026-09-16 审查背景：这 4 个页面此前**零测试**，因此以下缺陷全部逃逸 ——
 *   · HomePage 读 `status.height`（后端返回 `latestHeight`）→ TypeError →
 *     整页被 ErrorBoundary 替换
 *   · 详情页渲染出字面量 `undefined` 或空白数值
 * 本文件的 mock 数据全部来自 `__tests__/fixtures/backend.ts`，即**后端真实
 * 字段形状**。请勿改成「前端想象中的形状」——那正是缺陷得以逃逸的原因。
 */

vi.mock("../../api/client", async () => {
  const fx = await import("../fixtures/backend");
  return {
    ApiError: class ApiError extends Error {
      status = 0;
    },
    api: {
      getBlocks: vi.fn(async () => [fx.RPC_BLOCK]),
      getTransactions: vi.fn(async () => [fx.RPC_TRANSACTION]),
      getStatus: vi.fn(async () => fx.NODE_STATUS),
      getBlock: vi.fn(async () => fx.RPC_BLOCK),
      getTransaction: vi.fn(async () => fx.RPC_TRANSACTION),
      getAccount: vi.fn(async () => fx.ACCOUNT_INFO),
    },
    authenticatedRequest: vi.fn(),
    AUTH_HEADERS: {},
    isProtectedPath: () => false,
  };
});

afterEach(() => cleanup());

/** 页面不应渲染出字面量 undefined / null，也不应落到错误边界。 */
function expectNoUndefinedLeak() {
  const text = document.body.textContent ?? "";
  expect(text, "页面不应渲染出字面量 undefined").not.toContain("undefined");
  expect(text, "页面不应渲染出字面量 null").not.toContain("null");
  expect(text, "页面不应落到 ErrorBoundary fallback").not.toContain("页面渲染出错");
}

describe("HomePage", () => {
  it("节点状态返回 latestHeight 时不应崩溃，并展示真实高度", async () => {
    const HomePage = (await import("../../pages/HomePage")).default;
    render(
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText(/最新区块/)).toBeInTheDocument();
    });
    await waitFor(() => {
      // 1234 在 zh 环境下格式化为 1,234
      expect(document.body.textContent).toMatch(/1[,.]?234/);
    });
    expectNoUndefinedLeak();
  });

  it("应该展示区块与交易列表，并提供 /orchestration 入口", async () => {
    const HomePage = (await import("../../pages/HomePage")).default;
    render(
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>,
    );
    await waitFor(() => {
      // 高度同时出现在链状态与区块列表中，故用 getAllByText
      expect(screen.getAllByText(/1,234|1234/).length).toBeGreaterThan(0);
    });
    // 编排页入口（P1-2：此前该路由全仓零入口）
    const orchLink = screen.getByRole("link", { name: /支付编排/ });
    expect(orchLink).toHaveAttribute("href", "/orchestration");
    expectNoUndefinedLeak();
  });
});

describe("BlockDetailPage", () => {
  it("应该渲染后端实际返回的字段，且不出现 size/difficulty", async () => {
    const BlockDetailPage = (await import("../../pages/BlockDetailPage")).default;
    render(
      <MemoryRouter initialEntries={["/block/1234"]}>
        <Routes>
          <Route path="/block/:height" element={<BlockDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText(/父哈希/)).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).toContain("NEXproposerAddress");
    // 后端不返回 difficulty / size，UI 不应出现这两行（此前渲染为 undefined）
    expect(text, "不应出现「大小」行").not.toContain("大小");
    expect(text, "不应出现「难度」行").not.toContain("难度");
    expectNoUndefinedLeak();
  });
});

describe("TxDetailPage", () => {
  it("应该渲染后端实际返回的字段，且不出现 fee/nonce/type", async () => {
    const TxDetailPage = (await import("../../pages/TxDetailPage")).default;
    render(
      <MemoryRouter initialEntries={["/tx/abc"]}>
        <Routes>
          <Route path="/tx/:hash" element={<TxDetailPage />} />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText(/交易哈希/)).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).toContain("100 NEX");
    // 后端不返回 fee / nonce / type，UI 不应出现这三行
    // （此前 fee/nonce 渲染为空白数值，看起来像「手续费为空」）
    expect(text, "不应出现「手续费」行").not.toContain("手续费");
    expect(text, "不应出现「随机数」行").not.toContain("随机数");
    expect(text, "不应出现「类型」行").not.toContain("类型");
    expectNoUndefinedLeak();
  });
});

describe("AddressPage", () => {
  it("应该渲染余额与交易数，且不出现 nonce", async () => {
    const AddressPage = (await import("../../pages/AddressPage")).default;
    render(
      <MemoryRouter initialEntries={["/address/NEXaddress"]}>
        <Routes>
          <Route path="/address/:addr" element={<AddressPage />} />
        </Routes>
      </MemoryRouter>,
    );
    await waitFor(() => {
      expect(screen.getByText("1000")).toBeInTheDocument();
    });
    const text = document.body.textContent ?? "";
    expect(text).toContain("NEXaddress");
    expect(text, "不应出现「随机数」行（后端不返回 nonce）").not.toContain("随机数");
    expectNoUndefinedLeak();
  });
});
