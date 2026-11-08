import { describe, it, expect } from "vitest";
import { ApiError } from "../../api/client";
import { displayErrorMessage, type TranslateFn } from "../../utils/displayError";

/**
 * displayErrorMessage 的映射钉扎（2026-11-07 审计修复，发现 B5）。
 *
 * 修前的写法是 `err instanceof Error ? err.message : t("xx.loadFailed")` ——
 * 而**网络失败（TypeError）、超时（TimeoutError）、JSON 解析失败全都是 Error 实例**，
 * 于是 t() 兜底**永远不可达**，中文界面里冒出 `Failed to fetch` 这类英文技术串。
 * 本测试断言的就是"哪些情况取哪个 key"，以及"绝不把英文技术串透传出去"。
 */

// 用最小翻译表代替 i18n 运行时：只关心"取了哪个键"，不关心文案内容
const dict: Record<string, string> = {
  "common.notFound": "未找到",
  "common.errorService": "服务暂时不可用，请稍后重试",
  "common.errorNetwork": "无法连接到服务（网络中断或后端未启动）",
  "common.errorTimeout": "请求超时，请稍后重试",
  "common.errorCanceled": "请求已取消",
  "home.loadFailed": "加载失败",
  "address.loadFailed": "地址加载失败",
};
const t: TranslateFn = (key) => dict[key] ?? `MISSING:${key}`;

/** 构造一个 name 可控的错误（模拟 AbortSignal.timeout 的 DOMException / fetch 的 TypeError）。 */
function namedError(name: string, message: string): Error {
  const err = new Error(message);
  err.name = name;
  return err;
}

describe("displayErrorMessage（审计 B5）", () => {
  it("服务端给了文案时优先用它（网关/BFF 已给中文且具体）", () => {
    const err = new ApiError(500, "获取区块列表失败", true);
    expect(displayErrorMessage(err, t, "home.loadFailed")).toBe("获取区块列表失败");
  });

  it("服务端文案优先于 404/5xx 的通用文案", () => {
    expect(displayErrorMessage(new ApiError(404, "区块不存在", true), t, "home.loadFailed")).toBe(
      "区块不存在",
    );
  });

  it("404（无服务端文案）→ common.notFound", () => {
    const err = new ApiError(404, "Request failed: 404 Not Found");
    expect(displayErrorMessage(err, t, "home.loadFailed")).toBe(dict["common.notFound"]);
  });

  it("5xx（无服务端文案）→ common.errorService，不得透传本地英文兜底串", () => {
    const err = new ApiError(500, "Request failed: 500 Internal Server Error");
    const out = displayErrorMessage(err, t, "home.loadFailed");
    expect(out).toBe(dict["common.errorService"]);
    expect(out).not.toContain("Internal Server Error");
  });

  it("契约/解析类错误（status=0）→ 回落调用方文案", () => {
    const err = new ApiError(0, "/api/blocks: 响应缺少字段 [hash]（后端契约可能已变更）");
    expect(displayErrorMessage(err, t, "home.loadFailed")).toBe(dict["home.loadFailed"]);
  });

  it("回归：网络失败的文案不得是浏览器原始的 Failed to fetch", () => {
    const err = new TypeError("Failed to fetch");
    const out = displayErrorMessage(err, t, "home.loadFailed");
    expect(out).not.toContain("Failed to fetch");
    expect(out).toBe(dict["common.errorNetwork"]);
  });

  it("超时（AbortSignal.timeout 拒绝的 TimeoutError）→ common.errorTimeout", () => {
    // 真实环境里是 name="TimeoutError" 的 DOMException；按 name 判定与实现一致
    expect(
      displayErrorMessage(namedError("TimeoutError", "signal timed out"), t, "home.loadFailed"),
    ).toBe(dict["common.errorTimeout"]);
  });

  it("主动取消（AbortError）→ common.errorCanceled", () => {
    expect(displayErrorMessage(namedError("AbortError", "aborted"), t, "home.loadFailed")).toBe(
      dict["common.errorCanceled"],
    );
  });

  it("未知抛出物（字符串/undefined/普通 Error）→ 回落调用方文案", () => {
    expect(displayErrorMessage("boom", t, "address.loadFailed")).toBe(dict["address.loadFailed"]);
    expect(displayErrorMessage(undefined, t, "address.loadFailed")).toBe(
      dict["address.loadFailed"],
    );
    expect(displayErrorMessage(new Error("weird"), t, "address.loadFailed")).toBe(
      dict["address.loadFailed"],
    );
  });

  it("空白的服务端文案不算「有文案」，走通用映射", () => {
    const err = new ApiError(404, "   ", true);
    expect(displayErrorMessage(err, t, "home.loadFailed")).toBe(dict["common.notFound"]);
  });
});
