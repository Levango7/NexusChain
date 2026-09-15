import { describe, it, expect } from "vitest";
import { render, screen } from "../test-utils";
import { Badge } from "../../components/ui/Badge";

/**
 * Badge 组件测试。
 *
 * 范围说明：本文件验证**组件逻辑**（渲染哪个类、图标是否存在）。
 * 「类名是否真的生成了 CSS」由 `scripts/verify-build-artifacts.mjs` 校验 ——
 * className 断言无法发现 `var()` 色值导致透明度修饰符被丢弃、
 * 或主题映射遗漏导致工具类不生成的问题（2026-09-16 审查教训）。
 */
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
    expect(badge.className).toContain("text-muted");
    expect(badge.className).toContain("bg-surface-2");
  });

  it("tone=primary 应该应用 primary 样式", () => {
    const { container } = render(<Badge tone="primary">Primary</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-accent");
    expect(badge.className).toContain("bg-accent-soft");
  });

  it("tone=success 应该使用语义浅底 token（非 alpha 稀释）", () => {
    const { container } = render(<Badge tone="success">Success</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-success");
    expect(badge.className).toContain("bg-success-soft");
  });

  it("tone=warning 应该使用语义浅底 token", () => {
    const { container } = render(<Badge tone="warning">Warning</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-warn");
    expect(badge.className).toContain("bg-warn-soft");
  });

  it("tone=danger 应该使用语义浅底 token", () => {
    const { container } = render(<Badge tone="danger">Danger</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("text-danger");
    expect(badge.className).toContain("bg-danger-soft");
  });

  it("outlined=true 时应该带语义描边", () => {
    const { container } = render(
      <Badge tone="success" outlined>
        Success
      </Badge>,
    );
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("border");
    expect(badge.className).toContain("border-success/30");
  });

  // ── 图标契约（DESIGN.md §4：状态标签需「图标 + 文字，不只靠颜色」）──

  it("success/warning/danger 默认渲染图标（不依赖颜色传达状态）", () => {
    for (const tone of ["success", "warning", "danger"] as const) {
      const { container, unmount } = render(<Badge tone={tone}>x</Badge>);
      expect(
        container.querySelector("svg"),
        `tone=${tone} 应默认渲染图标`,
      ).not.toBeNull();
      unmount();
    }
  });

  it("neutral/primary 默认不渲染图标", () => {
    for (const tone of ["neutral", "primary"] as const) {
      const { container, unmount } = render(<Badge tone={tone}>x</Badge>);
      expect(container.querySelector("svg"), `tone=${tone} 不应有图标`).toBeNull();
      unmount();
    }
  });

  it("icon={null} 应该抑制默认图标", () => {
    const { container } = render(
      <Badge tone="success" icon={null}>
        Success
      </Badge>,
    );
    expect(container.querySelector("svg")).toBeNull();
  });

  it("icon 传入自定义节点时应该覆盖默认图标", () => {
    render(
      <Badge tone="success" icon={<span data-testid="custom-icon" />}>
        Success
      </Badge>,
    );
    expect(screen.getByTestId("custom-icon")).toBeInTheDocument();
  });

  it("图标应该带 aria-hidden（状态语义由文字承载）", () => {
    const { container } = render(<Badge tone="danger">FAILED</Badge>);
    expect(container.querySelector("svg")?.getAttribute("aria-hidden")).toBe("true");
  });

  // ── 基础样式与扩展 ──

  it("应该应用基础样式（inline-flex + gap + 圆角 + 字号）", () => {
    const { container } = render(<Badge>基础</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("inline-flex");
    expect(badge.className).toContain("items-center");
    expect(badge.className).toContain("gap-1");
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
    const { container } = render(<Badge className="custom-class">自定义</Badge>);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain("custom-class");
  });
});
