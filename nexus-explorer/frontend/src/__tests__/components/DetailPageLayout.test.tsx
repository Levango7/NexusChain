import { describe, it, expect } from "vitest";
import { render, screen } from "../test-utils";
import { MemoryRouter } from "react-router-dom";
import { DetailPageLayout } from "../../components/ui/DetailPageLayout";

/**
 * DetailPageLayout 测试 — 验证 loading / error / 正常三种态的渲染。
 *
 * P2: 补齐 DetailPageLayout 共享组件测试覆盖。
 *
 * 注意：DetailPageLayout 内部使用 <Link>，需要 <MemoryRouter> 包裹。
 */
const renderWithRouter = (ui: React.ReactElement) =>
  render(<MemoryRouter>{ui}</MemoryRouter>);

describe("DetailPageLayout 组件", () => {
  it("loading=true 时应该渲染 Loading 占位", () => {
    renderWithRouter(
      <DetailPageLayout loading loadingLabel="加载中...">
        <div data-testid="child">内容</div>
      </DetailPageLayout>,
    );
    expect(screen.queryByTestId("child")).not.toBeInTheDocument();
    expect(screen.getByText("加载中...")).toBeInTheDocument();
  });

  it("error 非空时应该渲染错误占位", () => {
    renderWithRouter(
      <DetailPageLayout error="出错了">
        <div data-testid="child">内容</div>
      </DetailPageLayout>,
    );
    expect(screen.queryByTestId("child")).not.toBeInTheDocument();
    expect(screen.getByText("出错了")).toBeInTheDocument();
  });

  it("loading 与 error 均为空时应该渲染 children + header", () => {
    renderWithRouter(
      <DetailPageLayout title="标题" backLabel="返回">
        <div data-testid="child">内容</div>
      </DetailPageLayout>,
    );
    expect(screen.getByTestId("child")).toBeInTheDocument();
    expect(screen.getByText("标题")).toBeInTheDocument();
    expect(screen.getByText("返回")).toBeInTheDocument();
  });

  it("未传 title 时不应渲染 h1", () => {
    const { container } = renderWithRouter(
      <DetailPageLayout backLabel="返回">
        <div>内容</div>
      </DetailPageLayout>,
    );
    expect(container.querySelector("h1")).toBeNull();
  });

  it("未传 backLabel 时只渲染 ArrowLeft 图标", () => {
    renderWithRouter(
      <DetailPageLayout title="标题">
        <div>内容</div>
      </DetailPageLayout>,
    );
    expect(screen.getByText("标题")).toBeInTheDocument();
    // 返回链接仍存在（含 svg 图标），但无文字
    expect(screen.getByRole("link")).toBeInTheDocument();
  });

  it("应该支持自定义 mainClassName", () => {
    const { container } = renderWithRouter(
      <DetailPageLayout mainClassName="custom-main">
        <div>内容</div>
      </DetailPageLayout>,
    );
    expect(container.querySelector(".custom-main")).toBeInTheDocument();
  });
});
