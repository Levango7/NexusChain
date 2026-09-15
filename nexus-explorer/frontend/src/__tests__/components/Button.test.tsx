import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "../test-utils";
import { Button } from "../../components/ui/Button";

/**
 * Button 组件测试。
 *
 * 2026-09-16 审查修复后同步更新：
 *  - primary 改用语义实底 token（bg-accent-solid），不再直接用 bg-accent
 *  - secondary 按 DESIGN.md §4 改为「透明底 + accent 描边 + accent 文字」
 *  - ghost 按 DESIGN.md §4 改为 surface-2 底
 *  - danger 改用 bg-danger-solid + text-danger-on（此前硬编码 text-white，
 *    在暗色主题下对比度仅 2.77:1）
 *  - 尺寸提升至满足触摸目标标准：sm 36 / md 44 / lg 48
 *  - 新增 loading 态
 *
 * 说明：本文件只验证组件逻辑（渲染哪些类/元素）。「类名是否真的生成了 CSS」
 * 由 scripts/verify-build-artifacts.mjs 校验。
 */
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

  // ── 变体 ──

  it("primary variant 应该使用语义实底 token", () => {
    render(<Button variant="primary">Primary</Button>);
    const btn = screen.getByRole("button", { name: "Primary" });
    expect(btn.className).toContain("bg-accent-solid");
    expect(btn.className).toContain("text-accent-on");
    expect(btn.className).toContain("hover:bg-accent-solid-hover");
  });

  it("secondary variant 应该为透明底 + accent 描边（DESIGN.md §4）", () => {
    render(<Button variant="secondary">Secondary</Button>);
    const btn = screen.getByRole("button", { name: "Secondary" });
    expect(btn.className).toContain("bg-transparent");
    expect(btn.className).toContain("border-accent");
    expect(btn.className).toContain("text-accent");
  });

  it("danger variant 应该使用 danger-solid + danger-on", () => {
    render(<Button variant="danger">Danger</Button>);
    const btn = screen.getByRole("button", { name: "Danger" });
    expect(btn.className).toContain("bg-danger-solid");
    expect(btn.className).toContain("text-danger-on");
  });

  it("ghost variant 应该为 surface-2 底（DESIGN.md §4）", () => {
    render(<Button variant="ghost">Ghost</Button>);
    const btn = screen.getByRole("button", { name: "Ghost" });
    expect(btn.className).toContain("bg-surface-2");
    expect(btn.className).toContain("text-fg");
  });

  // ── 尺寸（触摸目标）──

  it("sm 尺寸为 36px（满足 WCAG 2.2 SC 2.5.8 AA 的 24px）", () => {
    render(<Button size="sm">Small</Button>);
    const btn = screen.getByRole("button", { name: "Small" });
    expect(btn.className).toContain("h-9");
  });

  it("md 尺寸（默认）为 44px（满足 DESIGN.md §8 与 WCAG 2.5.5 AAA）", () => {
    render(<Button>Medium</Button>);
    const btn = screen.getByRole("button", { name: "Medium" });
    expect(btn.className).toContain("h-11");
  });

  it("lg 尺寸为 48px", () => {
    render(<Button size="lg">Large</Button>);
    const btn = screen.getByRole("button", { name: "Large" });
    expect(btn.className).toContain("h-12");
  });

  // ── 布局与图标 ──

  it("fullWidth=true 时应该应用 w-full 类", () => {
    render(<Button fullWidth>Full</Button>);
    const btn = screen.getByRole("button", { name: "Full" });
    expect(btn.className).toContain("w-full");
  });

  it("应该渲染 leadingIcon", () => {
    render(<Button leadingIcon={<span data-testid="leading-icon">★</span>}>With Icon</Button>);
    expect(screen.getByTestId("leading-icon")).toBeInTheDocument();
  });

  it("应该渲染 trailingIcon", () => {
    render(
      <Button trailingIcon={<span data-testid="trailing-icon">→</span>}>With Trailing</Button>,
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

  // ── loading 态（DESIGN.md §4 要求覆盖 loading）──

  it("loading=true 时应该禁用按钮并标注 aria-busy", async () => {
    const user = userEvent.setup();
    const handleClick = vi.fn();
    render(
      <Button loading onClick={handleClick}>
        保存
      </Button>,
    );
    const btn = screen.getByRole("button", { name: "保存" });
    expect(btn).toBeDisabled();
    expect(btn).toHaveAttribute("aria-busy", "true");
    await user.click(btn);
    expect(handleClick).not.toHaveBeenCalled();
  });

  it("loading=true 时应该渲染 spinner 并替换 leadingIcon", () => {
    const { container } = render(
      <Button loading leadingIcon={<span data-testid="leading-icon">★</span>}>
        保存
      </Button>,
    );
    expect(container.querySelector("svg")).not.toBeNull();
    expect(screen.queryByTestId("leading-icon")).toBeNull();
  });

  it("loading=true 且提供 loadingLabel 时应该替换文案", () => {
    render(
      <Button loading loadingLabel="保存中...">
        保存
      </Button>,
    );
    expect(screen.getByText("保存中...")).toBeInTheDocument();
    expect(screen.queryByText("保存")).toBeNull();
  });

  it("loading=false 时不应有 aria-busy", () => {
    render(<Button>普通</Button>);
    expect(screen.getByRole("button", { name: "普通" })).not.toHaveAttribute("aria-busy");
  });
});
