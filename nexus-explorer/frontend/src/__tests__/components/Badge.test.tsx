import { describe, it, expect } from "vitest";
import { render, screen } from "../test-utils";
import { Badge } from "../../components/ui/Badge";

describe("Badge 组件", () => {
  it("应该渲染 children 内容", () => {
    render(<Badge>SUCCEEDED</Badge>);
    expect(screen.getByText("SUCCEEDED")).toBeInTheDocument();
  });

  it("应该渲染为 span 元素", () => {
    const { container } = render(<Badge>文本</Badge>);
    expect(container.firstChild?.nodeName).toBe("SPAN");
  });

  it("默认 tone=neutral 应该应用 neutral 样式", () => {
    const { container } = render(<Badge>默认</Badge>);
    const badge = container.firstChild as HTMLElement;
    // soft 模式下 neutral 使用 bg-surface-2 text-muted
    expect(badge.className).toContain("text-muted");
  });

  it("tone=primary 应该应用 primary 样式", () => {
    const { container } = render(<Badge tone="primary">Primary</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-accent");
  });

  it("tone=success 应该应用 success 样式", () => {
    const { container } = render(<Badge tone="success">Success</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-success");
  });

  it("tone=warning 应该应用 warning 样式", () => {
    const { container } = render(<Badge tone="warning">Warning</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-warn");
  });

  it("tone=danger 应该应用 danger 样式", () => {
    const { container } = render(<Badge tone="danger">Danger</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-danger");
  });

  it("solid=true 时 tone=primary 应该使用实底样式", () => {
    const { container } = render(
      <Badge tone="primary" solid>
        Primary Solid
      </Badge>,
    );
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("bg-accent");
    expect(badge.className).toContain("text-accent-on");
  });

  it("solid=true 时 tone=success 应该使用实底样式", () => {
    const { container } = render(
      <Badge tone="success" solid>
        Success Solid
      </Badge>,
    );
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("bg-success");
  });

  it("solid=true 时 tone=warning 应该使用实底样式", () => {
    const { container } = render(
      <Badge tone="warning" solid>
        Warning Solid
      </Badge>,
    );
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("bg-warn");
  });

  it("solid=true 时 tone=danger 应该使用实底样式", () => {
    const { container } = render(
      <Badge tone="danger" solid>
        Danger Solid
      </Badge>,
    );
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("bg-danger");
  });

  it("应该应用基础样式（inline-flex + 圆角 + 字号）", () => {
    const { container } = render(<Badge>基础</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("inline-flex");
    expect(badge.className).toContain("rounded-sm");
    expect(badge.className).toContain("text-xs");
    expect(badge.className).toContain("font-medium");
  });

  it("应该支持 ReactNode 类型的 children", () => {
    render(
      <Badge>
        <span data-testid="badge-content">自定义内容</span>
      </Badge>,
    );
    expect(screen.getByTestId("badge-content")).toBeInTheDocument();
  });

  it("应该支持自定义 className", () => {
    const { container } = render(
      <Badge className="custom-class">自定义</Badge>,
    );
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("custom-class");
  });
});