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

  it("disableBackdropClose=true 时按 ESC 不应触发 onClose", async () => {
    const user = userEvent.setup();
    const handleClose = vi.fn();
    render(
      <Modal open={true} onClose={handleClose} disableBackdropClose title="标题">
        内容
      </Modal>,
    );
    await user.keyboard("{Escape}");
    expect(handleClose).not.toHaveBeenCalled();
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