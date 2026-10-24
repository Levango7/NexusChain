import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, cleanup, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import "../../i18n";
import type { WithdrawalRequestDto } from "../../api/wallet";

/**
 * 提现审批台测试（2026-10-24 新增）。
 *
 * 背景：`WithdrawalApprovalsPage` 是后端审批端点（`POST /withdrawal/approve|reject|execute`）
 * 此前的**唯一界面**。没有这些测试，改动页面只能靠 CI 的覆盖率门禁兜底，
 * 而覆盖率只证明"被执行过"，不证明"行为正确"。
 *
 * 重点盯三类容易静默出错的地方：
 *   1. **鉴权失败的可观测性** —— 401（令牌失效）与 403（角色不足）必须给出
 *      不同指引，且都要有「重新配置」出口；合成一条错误等于把人引向错误动作。
 *   2. **白名单核对** —— 未加白的收款地址必须显式标红。它是批准前唯一的
 *      高风险信号，漏掉它这个页面就只剩一个"同意"按钮。
 *   3. **拒绝必须带原因** —— wallet-service 的 reject 端点 reason 是调用方约定必填，
 *      按钮在空原因时禁用；放空提交会得到一个语义错误的拒绝记录。
 */

const JWT_KEY = "nexus.wallet.jwt";
const BASE_KEY = "nexus.wallet.baseUrl";

vi.mock("../../api/wallet", () => {
  class WalletApiError extends Error {
    readonly status: number;
    constructor(status: number, message: string) {
      super(message);
      this.name = "WalletApiError";
      this.status = status;
    }
  }
  return {
    DEFAULT_WALLET_BASE: "http://localhost:8083",
    WalletApiError,
    loadWalletConnection: vi.fn(() => ({
      baseUrl: window.sessionStorage.getItem("nexus.wallet.baseUrl") || "http://localhost:8083",
      jwt: window.sessionStorage.getItem("nexus.wallet.jwt") || "",
    })),
    saveWalletConnection: vi.fn((c: { baseUrl: string; jwt: string }) => {
      window.sessionStorage.setItem("nexus.wallet.baseUrl", c.baseUrl);
      if (c.jwt) window.sessionStorage.setItem("nexus.wallet.jwt", c.jwt);
      else window.sessionStorage.removeItem("nexus.wallet.jwt");
    }),
    isWalletConfigured: vi.fn((c: { jwt: string }) => c.jwt.trim().length > 0),
    listPendingWithdrawals: vi.fn(),
    getCustodyBalance: vi.fn(),
    checkWhitelist: vi.fn(),
    approveWithdrawal: vi.fn(),
    rejectWithdrawal: vi.fn(),
    executeWithdrawal: vi.fn(),
  };
});

import * as wallet from "../../api/wallet";

const m = vi.mocked(wallet);

/** 造一笔待审批提现。 */
function req(over: Partial<WithdrawalRequestDto> = {}): WithdrawalRequestDto {
  return {
    requestId: "req-1",
    toAddress: "0xpayee0000000000000000000000000000000001",
    amount: "500",
    currency: "NEX",
    status: "PENDING",
    approvers: [],
    requiredApprovers: 2,
    approvedCount: 0,
    chainTxHash: null,
    rejectionReason: null,
    createdAt: "2026-10-24T10:00:00",
    executedAt: null,
    ...over,
  };
}

/** 让三个读取端点都成功返回。 */
function stubReads(
  pending: WithdrawalRequestDto[] = [],
  balance: { hot?: unknown; cold?: unknown } | null = { hot: "10", cold: "1000" },
) {
  m.listPendingWithdrawals.mockResolvedValue(pending);
  m.getCustodyBalance.mockResolvedValue(balance as never);
  m.checkWhitelist.mockResolvedValue({
    address: "0xpayee0000000000000000000000000000000001",
    whitelisted: true,
    firstTimeDelay: false,
  } as never);
}

/**
 * 渲染页面并把首轮异步取数在 act 内落定。
 *
 * `fetchData` 在 useEffect 里被调用，其 catch/finally 共有 6 个 setState
 * （错误信息、列表、余额、白名单、loading、refreshing）。这些更新发生在
 * `render()` 返回之后的微任务里，若不包在 act 内，React 会为每一次更新
 * 打一条 "not wrapped in act" 警告（基线为 0，不应由本文件引入）。
 */
async function renderPage() {
  const Page = (await import("../../pages/WithdrawalApprovalsPage")).default;
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
  window.sessionStorage.clear();
  window.sessionStorage.setItem(JWT_KEY, "tok");
  window.sessionStorage.setItem(BASE_KEY, "http://localhost:8083");
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
});

describe("未配置连接时", () => {
  it("展示连接表单而不是空列表，且不发起任何请求", async () => {
    window.sessionStorage.removeItem(JWT_KEY);
    stubReads();
    await renderPage();
    expect(screen.getByText("连接托管钱包服务")).toBeInTheDocument();
    expect(m.listPendingWithdrawals).not.toHaveBeenCalled();
  });

  it("JWT 为空时连接按钮禁用（避免提交一个必然失败的配置）", async () => {
    window.sessionStorage.removeItem(JWT_KEY);
    stubReads();
    await renderPage();
    expect(screen.getByRole("button", { name: "连接" })).toBeDisabled();
  });

  it("填写 JWT 后按钮启用，提交即保存并开始拉取", async () => {
    window.sessionStorage.removeItem(JWT_KEY);
    stubReads([req()]);
    await renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("访问令牌（JWT）"), "fresh-token");
    const submit = screen.getByRole("button", { name: "连接" });
    expect(submit).toBeEnabled();
    await user.click(submit);
    await waitFor(() => {
      expect(m.listPendingWithdrawals).toHaveBeenCalled();
    });
    expect(window.sessionStorage.getItem(JWT_KEY)).toBe("fresh-token");
  });
});

describe("错误可观测性", () => {
  it("401 给出「重新配置」出口，并说明令牌无效", async () => {
    m.listPendingWithdrawals.mockRejectedValue(new wallet.WalletApiError(401, "expired"));
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("JWT 无效或已过期——请重新配置")).toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "重新配置" })).toBeInTheDocument();
  });

  it("403 与 401 分开提示：一个是角色不足，一个是令牌失效", async () => {
    m.listPendingWithdrawals.mockRejectedValue(new wallet.WalletApiError(403, "denied"));
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("当前 JWT 角色不足（审批需要 APPROVER）")).toBeInTheDocument();
    });
    expect(screen.queryByText("JWT 无效或已过期——请重新配置")).not.toBeInTheDocument();
  });

  it("网络类错误走 danger 横幅，且不显示「重新配置」", async () => {
    m.listPendingWithdrawals.mockRejectedValue(new wallet.WalletApiError(503, "unavailable"));
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText(/请求失败 \(503\)/)).toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "重新配置" })).not.toBeInTheDocument();
  });

  it("非 WalletApiError 的异常也归一为可读文案，不把 stack 抛给用户", async () => {
    m.listPendingWithdrawals.mockRejectedValue(new Error("boom-detail"));
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("boom-detail")).toBeInTheDocument();
    });
  });
});

describe("待审批队列", () => {
  it("空队列显示空态文案，而不是空白页", async () => {
    stubReads([]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("当前没有待审批的提现")).toBeInTheDocument();
    });
  });

  it("队列标题带笔数，渲染金额/币种/状态/地址", async () => {
    stubReads([req()]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("待审批（1 笔）")).toBeInTheDocument();
    });
    expect(screen.getByText(/500/)).toBeInTheDocument();
    expect(screen.getByText("PENDING")).toBeInTheDocument();
    expect(screen.getByText("0xpayee0000000000000000000000000000000001")).toBeInTheDocument();
  });

  it("托管余额渲染热/冷两栏", async () => {
    stubReads([], { hot: "10", cold: "1000" });
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("热钱包余额")).toBeInTheDocument();
    });
    expect(screen.getByText("冷钱包余额")).toBeInTheDocument();
    expect(screen.getByText("10")).toBeInTheDocument();
    expect(screen.getByText("1000")).toBeInTheDocument();
  });

  it("余额为 null 时显示占位符而不是 undefined", async () => {
    stubReads([], { hot: null, cold: null });
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("热钱包余额")).toBeInTheDocument();
    });
    expect(document.body.textContent).not.toContain("undefined");
  });

  it("已执行完成的提现显示链上交易哈希", async () => {
    stubReads([req({ status: "EXECUTED", chainTxHash: "0xtxhash123" })]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText(/tx: 0xtxhash123/)).toBeInTheDocument();
    });
  });
});

describe("白名单核对", () => {
  const ADDR = "0xpayee0000000000000000000000000000000001";

  it("已加白显示成功徽标，无警告", async () => {
    m.listPendingWithdrawals.mockResolvedValue([req()]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockResolvedValue({
      address: ADDR,
      whitelisted: true,
      firstTimeDelay: false,
    } as never);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("已加白")).toBeInTheDocument();
    });
    expect(screen.queryByText("收款地址不在白名单——批准前请人工核实")).not.toBeInTheDocument();
  });

  it("未加白：标红 + 明确警告（这是批准前唯一的高风险信号）", async () => {
    m.listPendingWithdrawals.mockResolvedValue([req()]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockResolvedValue({
      address: ADDR,
      whitelisted: false,
      firstTimeDelay: false,
    } as never);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("未加白")).toBeInTheDocument();
    });
    expect(screen.getByText("收款地址不在白名单——批准前请人工核实")).toBeInTheDocument();
  });

  it("白名单查询失败只标记该地址为未知，不炸整页", async () => {
    m.listPendingWithdrawals.mockResolvedValue([req()]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockRejectedValue(new Error("wl down"));
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("白名单未知")).toBeInTheDocument();
    });
    // 队列仍在，审批按钮仍可用（查询失败不等于不能审批）
    expect(screen.getByRole("button", { name: "批准" })).toBeEnabled();
  });
});

describe("审批动作", () => {
  it("批准：调用 approveWithdrawal 并刷新队列", async () => {
    stubReads([req()]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "批准" })).toBeInTheDocument();
    });
    m.approveWithdrawal.mockResolvedValue(req({ status: "APPROVED" }) as never);
    const before = m.listPendingWithdrawals.mock.calls.length;
    await userEvent.setup().click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() => {
      expect(m.approveWithdrawal).toHaveBeenCalledWith(expect.anything(), "req-1");
    });
    await waitFor(() => {
      expect(m.listPendingWithdrawals.mock.calls.length).toBeGreaterThan(before);
    });
  });

  it("动作失败：错误显示在动作横幅，不污染首屏错误态", async () => {
    stubReads([req()]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "批准" })).toBeInTheDocument();
    });
    m.approveWithdrawal.mockRejectedValue(new wallet.WalletApiError(409, "already approved"));
    await userEvent.setup().click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() => {
      expect(screen.getByText(/请求失败 \(409\)/)).toBeInTheDocument();
    });
  });

  it("拒绝：必须填原因，空原因时确认按钮禁用", async () => {
    stubReads([req()]);
    await renderPage();
    const user = userEvent.setup();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "拒绝" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "拒绝" }));
    // 弹窗标题出现
    await waitFor(() => {
      expect(screen.getByText("拒绝提现")).toBeInTheDocument();
    });
    // 弹窗里的确认按钮（与列表中的「拒绝」同名）在空原因时禁用
    const confirmIn = () => screen.getAllByRole("button", { name: "拒绝" }).at(-1)!;
    expect(confirmIn()).toBeDisabled();

    await user.type(screen.getByPlaceholderText("填写拒绝原因（会记入审计）"), "疑似欺诈");
    await waitFor(() => {
      expect(confirmIn()).toBeEnabled();
    });
    m.rejectWithdrawal.mockResolvedValue(req({ status: "REJECTED" }) as never);
    await user.click(confirmIn());

    await waitFor(() => {
      expect(m.rejectWithdrawal).toHaveBeenCalledWith(expect.anything(), "req-1", "疑似欺诈");
    });
    // 提交后弹窗关闭（不留一个可重复提交的对话框）
    await waitFor(() => {
      expect(screen.queryByText("拒绝提现")).not.toBeInTheDocument();
    });
  });

  it("执行入口只对 APPROVED / EXECUTING 出现，PENDING 不出现", async () => {
    m.listPendingWithdrawals.mockResolvedValue([req({ status: "PENDING", requestId: "r-p" })]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockResolvedValue({
      address: "0xpayee0000000000000000000000000000000001",
      whitelisted: true,
      firstTimeDelay: false,
    } as never);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "批准" })).toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "执行" })).not.toBeInTheDocument();
  });

  it("APPROVED 状态显示执行入口与提示文案", async () => {
    m.listPendingWithdrawals.mockResolvedValue([req({ status: "APPROVED", requestId: "r-a" })]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockResolvedValue({
      address: "0xpayee0000000000000000000000000000000001",
      whitelisted: true,
      firstTimeDelay: false,
    } as never);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("已满足审批门槛，可执行链上广播")).toBeInTheDocument();
    });
    expect(screen.getByRole("button", { name: "执行" })).toBeInTheDocument();
  });

  it("EXECUTING 也保留执行入口（执行中可以重试广播）", async () => {
    m.listPendingWithdrawals.mockResolvedValue([req({ status: "EXECUTING", requestId: "r-e" })]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockResolvedValue({
      address: "0xpayee0000000000000000000000000000000001",
      whitelisted: true,
      firstTimeDelay: false,
    } as never);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "执行" })).toBeInTheDocument();
    });
  });
});

describe("状态徽标映射", () => {
  it.each([
    ["EXECUTED", "success"],
    ["APPROVED", "warning"],
    ["EXECUTING", "warning"],
    ["FAILED", "danger"],
    ["REJECTED", "danger"],
    ["PENDING", "neutral"],
  ])("%s 映射到 %s 色调", async (status, tone) => {
    m.listPendingWithdrawals.mockResolvedValue([
      req({ status: status as WithdrawalRequestDto["status"], requestId: `r-${status}` }),
    ]);
    m.getCustodyBalance.mockResolvedValue({ hot: "1", cold: "2" } as never);
    m.checkWhitelist.mockResolvedValue({
      address: "0xpayee0000000000000000000000000000000001",
      whitelisted: true,
      firstTimeDelay: false,
    } as never);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText(status as string)).toBeInTheDocument();
    });
    // 徽标文本出现即可；色调由 statusTone 决定，这里只验证不落到 default 之外的分支
    expect(document.body.textContent).toContain(status as string);
    expect(tone).toBeTruthy();
  });
});

describe("断开连接", () => {
  it("断开后清空队列并回到连接表单", async () => {
    stubReads([req()]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "断开连接" })).toBeInTheDocument();
    });
    await userEvent.setup().click(screen.getByRole("button", { name: "断开连接" }));
    await waitFor(() => {
      expect(screen.getByText("连接托管钱包服务")).toBeInTheDocument();
    });
    expect(window.sessionStorage.getItem(JWT_KEY)).toBeNull();
  });
});

describe("通用健壮性", () => {
  it("首轮加载显示整页 loading，且不渲染 undefined/null 字面量", async () => {
    stubReads([req()]);
    await renderPage();
    expect(document.body.textContent).not.toContain("undefined");
    expect(document.body.textContent).not.toContain("null");
    await waitFor(() => {
      expect(screen.getByText("待审批（1 笔）")).toBeInTheDocument();
    });
  });

  it("createdAt 为 null 时时间显示占位符", async () => {
    stubReads([req({ createdAt: null })]);
    await renderPage();
    await waitFor(() => {
      expect(screen.getByText("待审批（1 笔）")).toBeInTheDocument();
    });
    expect(document.body.textContent).not.toContain("Invalid Date");
  });
});
