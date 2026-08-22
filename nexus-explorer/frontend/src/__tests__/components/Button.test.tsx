import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "../test-utils";
import { Button } from "../../components/ui/Button";

describe("Button 组件", () => {
  it("应该渲染按钮文本", () => {
    render(<Button>点击我</Button>);
    expect(screen.getByText("点击我")).toBeInTheDocument();
  });

  it("应该渲染为 button 元素", () => {
    render(<Button>按钮</Button>);
    expect(screen.getByRole("button", { name: "按钮" })).toBeInTheDocument();
  });

  it("点击时应该触发 onClick 回调", async () => {
    const user = userEvent.setup();
    const handleClick = vi.fn();
    render(<Button onClick={handleClick}>点击</Button>);
    await user.click(screen.getByRole("button", { name: "点击" }));
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it("disabled 状态下点击不应触发 onClick", async () => {
    const user = userEvent.setup();
    const handleClick = vi.fn();
    render(
      <Button onClick={handleClick} disabled>
        禁用
      </Button>,
    );
    const btn = screen.getByRole("button", { name: "禁用" });
    expect(btn).toBeDisabled();
    await user.click(btn);
    expect(handleClick).not.toHaveBeenCalled();
  });

  it("应该应用 primary variant 样式", () => {
    render(<Button variant="primary">Primary</Button>);
    const btn = screen.getByRole("button", { name: "Primary" });
    expect(btn.className).toContain("bg-accent");
    expect(btn.className).toContain("text-accent-on");
  });

  it("应该应用 secondary variant 样式", () => {
    render(<Button variant="secondary">Secondary</Button>);
    const btn = screen.getByRole("button", { name: "Secondary" });
    expect(btn.className).toContain("bg-surface");
    expect(btn.className).toContain("border");
  });

  it("应该应用 danger variant 样式", () => {
    render(<Button variant="danger">Danger</Button>);
    const btn = screen.getByRole("button", { name: "Danger" });
    expect(btn.className).toContain("bg-danger");
  });

  it("应该应用 ghost variant 样式", () => {
    render(<Button variant="ghost">Ghost</Button>);
    const btn = screen.getByRole("button", { name: "Ghost" });
    expect(btn.className).toContain("bg-transparent");
  });

  it("应该应用 sm 尺寸样式", () => {
    render(<Button size="sm">Small</Button>);
    const btn = screen.getByRole("button", { name: "Small" });
    expect(btn.className).toContain("h-7");
  });

  it("应该应用 md 尺寸样式（默认）", () => {
    render(<Button>Medium</Button>);
    const btn = screen.getByRole("button", { name: "Medium" });
    expect(btn.className).toContain("h-9");
  });

  it("应该应用 lg 尺寸样式", () => {
    render(<Button size="lg">Large</Button>);
    const btn = screen.getByRole("button", { name: "Large" });
    expect(btn.className).toContain("h-11");
  });

  it("fullWidth=true 时应该应用 w-full 类", () => {
    render(<Button fullWidth>Full</Button>);
    const btn = screen.getByRole("button", { name: "Full" });
    expect(btn.className).toContain("w-full");
  });

  it("应该渲染 leadingIcon", () => {
    render(
      <Button leadingIcon={<span data-testid="leading-icon">★</span>}>
        With Icon
      </Button>,
    );
    expect(screen.getByTestId("leading-icon")).toBeInTheDocument();
  });

  it("应该渲染 trailingIcon", () => {
    render(
      <Button trailingIcon={<span data-testid="trailing-icon">→</span>}>
        With Trailing
      </Button>,
    );
    expect(screen.getByTestId("trailing-icon")).toBeInTheDocument();
  });

  it("应该透传原生 button 属性", () => {
    render(
      <Button type="submit" aria-label="提交表单">
        提交
      </Button>,
    );
    const btn = screen.getByRole("button", { name: "提交表单" });
    expect(btn).toHaveAttribute("type", "submit");
  });
});