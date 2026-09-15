import { describe, it, expect, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { render, screen } from "../test-utils";
import { Modal } from "../../components/ui/Modal";

describe("Modal 组件", () => {
  it("open=false 时不应渲染任何内容", () => {
    const { container } = render(
      <Modal open={false} onClose={() => {}}>
        内容
      </Modal>,
    );
    expect(container.firstChild).toBeNull();
  });

  it("open=true 时应该渲染 dialog", () => {
    render(
      <Modal open={true} onClose={() => {}} title="弹窗标题">
        弹窗内容
      </Modal>,
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("应该渲染 title 标题", () => {
    render(
      <Modal open={true} onClose={() => {}} title="我的弹窗">
        内容
      </Modal>,
    );
    expect(screen.getByText("我的弹窗")).toBeInTheDocument();
  });

  it("应该渲染 children 内容", () => {
    render(
      <Modal open={true} onClose={() => {}} title="标题">
        弹窗主体内容
      </Modal>,
    );
    expect(screen.getByText("弹窗主体内容")).toBeInTheDocument();
  });

  it("应该渲染 footer 底部区域", () => {
    render(
      <Modal
        open={true}
        onClose={() => {}}
        title="标题"
        footer={<button data-testid="modal-footer-btn">确认</button>}
      >
        内容
      </Modal>,
    );
    expect(screen.getByTestId("modal-footer-btn")).toBeInTheDocument();
  });

  it("应该设置 aria-modal=true", () => {
    render(
      <Modal open={true} onClose={() => {}} title="标题">
        内容
      </Modal>,
    );
    expect(screen.getByRole("dialog")).toHaveAttribute("aria-modal", "true");
  });

  it("点击关闭按钮应该触发 onClose", async () => {
    const user = userEvent.setup();
    const handleClose = vi.fn();
    render(
      <Modal open={true} onClose={handleClose} title="标题">
        内容
      </Modal>,
    );
    // 关闭按钮带 aria-label="关闭弹窗"
    const closeBtn = screen.getByRole("button", { name: "关闭弹窗" });
    await user.click(closeBtn);
    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it("点击遮罩应该触发 onClose", async () => {
    const user = userEvent.setup();
    const handleClose = vi.fn();
    const { container } = render(
      <Modal open={true} onClose={handleClose} title="标题">
        内容
      </Modal>,
    );
    // 遮罩是 dialog 内第一个 div，aria-hidden=true
    const backdrop = container.querySelector('[aria-hidden="true"]') as HTMLElement;
    await user.click(backdrop);
    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it("disableBackdropClose=true 时点击遮罩不应触发 onClose", async () => {
    const user = userEvent.setup();
    const handleClose = vi.fn();
    const { container } = render(
      <Modal open={true} onClose={handleClose} disableBackdropClose title="标题">
        内容
      </Modal>,
    );
    const backdrop = container.querySelector('[aria-hidden="true"]') as HTMLElement;
    await user.click(backdrop);
    expect(handleClose).not.toHaveBeenCalled();
  });

  it("按 ESC 键应该触发 onClose", async () => {
    const user = userEvent.setup();
    const handleClose = vi.fn();
    render(
      <Modal open={true} onClose={handleClose} title="标题">
        内容
      </Modal>,
    );
    await user.keyboard("{Escape}");
    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it("disableBackdropClose=true 时按 ESC 仍应触发 onClose（ESC 与遮罩是两个独立通道）", async () => {
    // 2026-09-16 审查修复：此前 disableBackdropClose 同时禁用了 ESC，
    // 使强确认场景下键盘用户无法退出对话框，违反 WCAG 2.1.2（No Keyboard Trap）。
    const user = userEvent.setup();
    const handleClose = vi.fn();
    render(
      <Modal open={true} onClose={handleClose} disableBackdropClose title="标题">
        内容
      </Modal>,
    );
    await user.keyboard("{Escape}");
    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  // ── 焦点管理（2026-09-16 新增）──

  it("打开时应该把焦点移入对话框内", () => {
    render(
      <Modal open={true} onClose={() => {}} title="标题">
        <button type="button">第一个</button>
        <button type="button">第二个</button>
      </Modal>,
    );
    const dialog = screen.getByRole("dialog");
    expect(dialog.contains(document.activeElement)).toBe(true);
  });

  it("关闭后应该把焦点归还给触发元素", () => {
    const trigger = document.createElement("button");
    trigger.textContent = "打开";
    document.body.appendChild(trigger);
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    const { rerender } = render(
      <Modal open={true} onClose={() => {}} title="标题">
        内容
      </Modal>,
    );
    // open 由 true → false 触发 effect cleanup，焦点应归还
    rerender(
      <Modal open={false} onClose={() => {}} title="标题">
        内容
      </Modal>,
    );
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });

  it("aria-labelledby 应该指向实际存在的标题元素（id 唯一）", () => {
    render(
      <Modal open={true} onClose={() => {}} title="唯一标题">
        内容
      </Modal>,
    );
    const dialog = screen.getByRole("dialog");
    const labelledBy = dialog.getAttribute("aria-labelledby");
    expect(labelledBy).toBeTruthy();
    expect(document.getElementById(labelledBy as string)?.textContent).toBe("唯一标题");
  });

  it("open=true 时应该锁定 body 滚动", () => {
    render(
      <Modal open={true} onClose={() => {}} title="标题">
        内容
      </Modal>,
    );
    expect(document.body.style.overflow).toBe("hidden");
  });

  it("应该支持自定义 maxWidth 类", () => {
    const { container } = render(
      <Modal open={true} onClose={() => {}} maxWidth="max-w-lg">
        内容
      </Modal>,
    );
    // 弹窗主体是 relative w-full max-w-* 的 div
    const modalBody = container.querySelector(".relative") as HTMLElement;
    expect(modalBody.className).toContain("max-w-lg");
  });
});
