import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "../test-utils";
import { Card } from "../../components/ui/Card";

describe("Card 组件", () => {
  it("应该渲染 children 内容", () => {
    render(<Card>卡片内容</Card>);
    expect(screen.getByText("卡片内容")).toBeInTheDocument();
  });

  it("应该渲染 title 标题", () => {
    render(<Card title="卡片标题">内容</Card>);
    expect(screen.getByText("卡片标题")).toBeInTheDocument();
  });

  it("应该渲染 subtitle 副标题", () => {
    render(
      <Card title="主标题" subtitle="副标题">
        内容
      </Card>,
    );
    expect(screen.getByText("主标题")).toBeInTheDocument();
    expect(screen.getByText("副标题")).toBeInTheDocument();
  });

  it("应该渲染 actions 操作区", () => {
    render(
      <Card actions={<button data-testid="card-action">操作</button>}>
        内容
      </Card>,
    );
    expect(screen.getByTestId("card-action")).toBeInTheDocument();
  });

  it("应该渲染 footer 底部区域", () => {
    render(<Card footer={<div data-testid="card-footer">底部</div>}>内容</Card>);
    expect(screen.getByTestId("card-footer")).toBeInTheDocument();
  });

  it("没有 title 和 actions 时不应渲染 header", () => {
    const { container } = render(<Card>仅内容</Card>);
    // header 区域使用 mb-3 类，没有 title/actions 时不应出现
    const headers = container.querySelectorAll(".mb-3");
    expect(headers).toHaveLength(0);
  });

  it("compact=true 时应该应用 p-3 类", () => {
    const { container } = render(<Card compact>紧凑</Card>);
    const card = container.firstChild as HTMLElement;
    expect(card.className).toContain("p-3");
  });

  it("compact=false（默认）时应该应用 p-5 类", () => {
    const { container } = render(<Card>默认</Card>);
    const card = container.firstChild as HTMLElement;
    expect(card.className).toContain("p-5");
  });

  it("interactive=true 时应该设置 role=button 和 tabIndex", () => {
    const { container } = render(<Card interactive>可点击</Card>);
    const card = container.firstChild as HTMLElement;
    expect(card).toHaveAttribute("role", "button");
    expect(card).toHaveAttribute("tabindex", "0");
  });

  it("interactive=false（默认）时不应设置 role=button", () => {
    const { container } = render(<Card>不可点击</Card>);
    const card = container.firstChild as HTMLElement;
    expect(card).not.toHaveAttribute("role");
    expect(card).not.toHaveAttribute("tabindex");
  });

  it("interactive=true 点击时应该触发 onClick", async () => {
    const user = userEvent.setup();
    const handleClick = vi.fn();
    const { container } = render(
      <Card interactive onClick={handleClick}>
        点击我
      </Card>,
    );
    await user.click(container.firstChild as HTMLElement);
    expect(handleClick).toHaveBeenCalledTimes(1);
  });

  it("interactive=false 点击时不应触发 onClick", async () => {
    const user = userEvent.setup();
    const handleClick = vi.fn();
    const { container } = render(
      <Card onClick={handleClick}>不可点击</Card>,
    );
    await user.click(container.firstChild as HTMLElement);
    expect(handleClick).not.toHaveBeenCalled();
  });

  it("应该应用基础卡片样式", () => {
    const { container } = render(<Card>样式</Card>);
    const card = container.firstChild as HTMLElement;
    expect(card.className).toContain("bg-surface");
    expect(card.className).toContain("border");
    expect(card.className).toContain("rounded-lg");
  });

  it("应该支持 ReactNode 类型的 title", () => {
    render(
      <Card title={<span data-testid="node-title">节点标题</span>}>内容</Card>,
    );
    expect(screen.getByTestId("node-title")).toBeInTheDocument();
  });
});