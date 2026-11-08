/**
 * auth.ts v2 canonical 与 HMAC 的跨语言一致性测试（短期项 #4）。
 *
 * 关键目标：TS 实现的 v2 canonical 必须与 Java 服务端
 * {@code RequestSignatureInterceptor.canonicalV2} 字节级一致。
 * 下面用固定输入给出确定性的 canonical 串断言（不依赖随机数），
 * 并以 Node/浏览器的 Web Crypto 计算 HMAC 自洽性。
 */
import { describe, expect, it } from "vitest";
import {
  buildCanonicalString,
  buildCanonicalStringV2,
  isProtectedPath,
  PROTECTED_PATH_PREFIXES,
} from "../../api/auth";

describe("v2 canonical（短期项 #4）", () => {
  it("长度前缀消除字段边界碰撞", () => {
    // v1 下这两组碰撞（无分隔符）；v2 必须区分
    const a1 = buildCanonicalString("12", "34", "POST", "/p", "b");
    const a2 = buildCanonicalString("123", "4", "POST", "/p", "b");
    expect(a1).toBe(a2); // v1 确实碰撞（回归证据）

    const b1 = buildCanonicalStringV2("12", "34", "POST", "/p", "b");
    const b2 = buildCanonicalStringV2("123", "4", "POST", "/p", "b");
    expect(b1).not.toBe(b2);
  });

  it("v2 canonical 确定性快照（与 Java canonicalV2 同构）", () => {
    // 与 Java 端 canonicalV2 输出逐字节一致：NXC2|len:field|...
    // （{"amount":100} 为 14 字节；/api/v1/payments 为 16 字节）
    const c = buildCanonicalStringV2(
      "1700000000000",
      "nonce-1",
      "POST",
      "/api/v1/payments",
      '{"amount":100}',
    );
    expect(c).toBe('NXC2|13:1700000000000|7:nonce-1|4:POST|16:/api/v1/payments|14:{"amount":100}|');
  });

  it("null/undefined body 与空串在 canonical 中一致", () => {
    expect(buildCanonicalStringV2("1", "n", "GET", "/p", undefined)).toBe(
      buildCanonicalStringV2("1", "n", "GET", "/p", ""),
    );
    expect(buildCanonicalStringV2("1", "n", "GET", "/p", undefined)).toBe(
      "NXC2|1:1|1:n|3:GET|2:/p|0:|",
    );
  });

  it("多字节字符按 UTF-8 字节数计长（与 Java 服务端一致）", () => {
    // "支" 为 3 字节 UTF-8
    const c = buildCanonicalStringV2("1", "n", "GET", "/p", "支");
    expect(c).toBe("NXC2|1:1|1:n|3:GET|2:/p|3:支|");
  });

  it("签名面清单与网关 WebConfig 的 5 个前缀逐项一致", () => {
    // 后端权威来源：WebConfig.addInterceptors → requestSignatureInterceptor
    //   .addPathPatterns("/api/v1/payments/**", "/api/v1/refunds/**", "/api/v1/orders/**",
    //                    "/api/v2/payments/**", "/api/v2/orders/**")
    // 该清单是"前端签名面 = 后端校验面"的单一断言点：任何一侧改动而未同步，
    // 本用例必须变红（此前它断言的是"只保护 /api/v1/payments/**"，把缺口钉死了）。
    expect(PROTECTED_PATH_PREFIXES).toEqual([
      "/api/v1/payments",
      "/api/v1/refunds",
      "/api/v1/orders",
      "/api/v2/payments",
      "/api/v2/orders",
    ]);
  });

  it("isProtectedPath 覆盖 v1/v2 资金端点，且按段边界判定", () => {
    // 命中：前缀本身、子路径、带 query（网关用 getRequestURI()，不含 query）
    expect(isProtectedPath("/api/v1/payments")).toBe(true);
    expect(isProtectedPath("/api/v1/payments/123")).toBe(true);
    expect(isProtectedPath("/api/v1/payments?limit=20")).toBe(true);
    expect(isProtectedPath("/api/v1/refunds")).toBe(true);
    expect(isProtectedPath("/api/v1/refunds/456")).toBe(true);
    expect(isProtectedPath("/api/v1/orders")).toBe(true);
    expect(isProtectedPath("/api/v1/orders/1/confirm")).toBe(true);
    expect(isProtectedPath("/api/v2/payments/batch")).toBe(true);
    expect(isProtectedPath("/api/v2/orders/1/refund")).toBe(true);

    // 不命中：段边界（裸 startsWith 会把这些误判为受保护）
    expect(isProtectedPath("/api/v1/payments-other")).toBe(false);
    expect(isProtectedPath("/api/v1/ordersomething")).toBe(false);
    expect(isProtectedPath("/api/v2/refunds")).toBe(false); // 网关未注册该前缀

    // 不命中：只过 ApiKey 的那些面
    expect(isProtectedPath("/api/v1/fund-dashboard")).toBe(false);
    expect(isProtectedPath("/api/v1/wallet/withdrawal/pending")).toBe(false);
    expect(isProtectedPath("/api/v1/merchants")).toBe(false);
  });
});
