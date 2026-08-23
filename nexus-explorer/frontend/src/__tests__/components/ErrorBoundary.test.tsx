import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "../test-utils";
import { ErrorBoundary } from "../../components/ui/ErrorBoundary";

/**
 * ErrorBoundary 测试 — 验证错误捕获、fallback 渲染、reset 行为。
 *
 * P2: 补齐 ErrorBoundary 测试覆盖。
 */
describe("ErrorBoundary 组件", () => {
  it("无错误时应该渲染 children", () => {
    render(
      <ErrorBoundary>
        <div data-testid="child">正常内容</div>
      </ErrorBoundary>,
    );
    expect(screen.getByTestId("child")).toBeInTheDocument();
  });

  it("子组件抛错时应该渲染默认 fallback", () => {
    // 抑制 console.error 噪音（React 18 会打印完整错误堆栈）
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const Boom: React.FC = () => {
      throw new Error("boom");
    };
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("boom")).toBeInTheDocument();
    spy.mockRestore();
  });

  it("子组件抛错时应该渲染 i18n 文案（页面渲染出错）", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const Boom: React.FC = () => {
      throw new Error("custom error");
    };
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    // i18n 默认 zh → common.pageError = "页面渲染出错"
    expect(screen.getByText("页面渲染出错")).toBeInTheDocument();
    spy.mockRestore();
  });

  it("error.message 为空时应该显示未知错误兜底", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const Boom: React.FC = () => {
      throw new Error("");
    };
    render(
      <ErrorBoundary>
        <Boom />
      </ErrorBoundary>,
    );
    // i18n 默认 zh → common.unknownError = "未知错误"
    expect(screen.getByText("未知错误")).toBeInTheDocument();
    spy.mockRestore();
  });

  it("点击重试按钮应该清除错误状态", async () => {
    const user = userEvent.setup();
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    let shouldThrow = true;
    const Flaky: React.FC = () => {
      if (shouldThrow) throw new Error("flaky");
      return <div data-testid="recovered">恢复</div>;
    };
    render(
      <ErrorBoundary>
        <Flaky />
      </ErrorBoundary>,
    );
    expect(screen.getByRole("alert")).toBeInTheDocument();
    // 点击重试
    const retryBtn = screen.getByRole("button", { name: "重试" });
    shouldThrow = false;
    await user.click(retryBtn);
    expect(screen.getByTestId("recovered")).toBeInTheDocument();
    spy.mockRestore();
  });

  it("应该支持自定义 fallback 渲染函数", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const Boom: React.FC = () => {
      throw new Error("custom");
    };
    render(
      <ErrorBoundary
        fallback={(err, reset) => (
          <div data-testid="custom-fallback">
            <span data-testid="err-msg">{err.message}</span>
            <button data-testid="reset-btn" onClick={reset}>
              自定义重试
            </button>
          </div>
        )}
      >
        <Boom />
      </ErrorBoundary>,
    );
    expect(screen.getByTestId("custom-fallback")).toBeInTheDocument();
    expect(screen.getByTestId("err-msg")).toHaveTextContent("custom");
    spy.mockRestore();
  });

  it("应该调用 onError 回调", () => {
    const spy = vi.spyOn(console, "error").mockImplementation(() => {});
    const onError = vi.fn();
    const Boom: React.FC = () => {
      throw new Error("reported");
    };
    render(
      <ErrorBoundary onError={onError}>
        <Boom />
      </ErrorBoundary>,
    );
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onError.mock.calls[0][0]).toBeInstanceOf(Error);
    expect(onError.mock.calls[0][0].message).toBe("reported");
    spy.mockRestore();
  });
});