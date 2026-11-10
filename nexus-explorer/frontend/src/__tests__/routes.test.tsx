import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * 路由可达性守卫。
 *
 * 2026-09-16 审查发现的缺陷：`/orchestration` 路由在全仓**只有 App.tsx 一处
 * 出现**（路由定义本身），没有任何 `<Link to>` 或 `navigate()` 指向它 ——
 * 302 行的支付编排控制台只能手敲 URL 访问，「配置凭证 → 验证凭证」的闭环
 * 在 UI 上断开。这类缺陷无法被组件测试或类型检查发现（路由定义与入口分处
 * 两个文件，各自都「正确」）。
 *
 * 本测试遍历 App.tsx 声明的每条路由，断言其静态路径段在源码中至少被一个
 * 导航入口引用。
 */

const here = dirname(fileURLToPath(import.meta.url));
const SRC = resolve(here, "..");

function collectSources(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) collectSources(p, out);
    else if (/\.(tsx|ts)$/.test(e.name) && !/\.test\./.test(e.name)) out.push(p);
  }
  return out;
}

/** 从 App.tsx 提取路由 path。 */
function readRoutePaths(): string[] {
  const app = readFileSync(join(SRC, "App.tsx"), "utf8");
  return [...app.matchAll(/path="([^"]+)"/g)].map((m) => m[1]);
}

/** 提取源码中所有导航入口的目标字符串（含模板字面量）。 */
function readNavTargets(): string[] {
  const targets: string[] = [];
  const patterns = [
    /to="([^"]*)"/g,
    /to=\{`([^`]*)`\}/g,
    /navigate\(\s*"([^"]*)"/g,
    /navigate\(\s*`([^`]*)`/g,
    /<Route\s+path="([^"]+)"/g, // 排除自引用
  ];
  for (const f of collectSources(SRC)) {
    if (f.endsWith("App.tsx")) continue; // 路由定义文件本身不算入口
    const s = readFileSync(f, "utf8");
    for (const re of patterns.slice(0, 4)) {
      for (const m of s.matchAll(re)) targets.push(m[1]);
    }
  }
  return targets;
}

/**
 * 路由的静态路径段（去掉 `:param`、空段、以及兜底通配 `*`）。
 *
 * `*` 是 catch-all：它**不该**有导航入口（它就是"没有任何入口通向它"时才有用），
 * 故从静态段里排除 —— 否则下面的「每条路由都要有入口」会把兜底路由判成不可达。
 */
function staticSegments(path: string): string[] {
  return path.split("/").filter((s) => s && !s.startsWith(":") && s !== "*");
}

describe("路由可达性", () => {
  const routes = readRoutePaths();
  const targets = readNavTargets();

  it("App.tsx 应该声明了路由", () => {
    expect(routes.length).toBeGreaterThan(0);
  });

  it('必须声明兜底路由 path="*"（否则未匹配路径渲染 null ⇒ 白屏）', () => {
    // 2026-11-07 审计发现 B10：App.tsx 曾有 11 条路由却没有兜底 —— 任意未匹配路径
    // 下 React Router 渲染 null，`<div id="root">` 保持空 ⇒ **纯白屏**
    // （实测 bodyHtmlLen=80、rootChildCount=0、bodyTextLen=0）。
    // 且本仓没有承载导航的布局路由（首页自己渲染导航、其它页渲染「返回」链接），
    // 所以未匹配时用户既看不到导航也看不到返回，被完全困在白屏里。
    // 本断言把兜底钉住，防止将来被误删。
    expect(routes).toContain("*");
  });

  it("每条非根路由都应该至少有一个导航入口", () => {
    const unreachable: string[] = [];
    for (const route of routes) {
      const segs = staticSegments(route);
      if (segments_empty(segs)) continue; // "/" 无需入口
      const reachable = targets.some((t) => segs.every((s) => t.includes(s)));
      if (!reachable) unreachable.push(route);
    }
    expect(
      unreachable,
      `以下路由在全仓无任何 <Link to> / navigate() 指向（页面不可达）：${unreachable.join(", ")}`,
    ).toEqual([]);
  });

  it("入口目标不应指向未声明的路由（防拼写错误）", () => {
    const declared = routes.map((r) => staticSegments(r)).filter((s) => s.length > 0);
    const orphans = targets.filter((t) => {
      if (!t.startsWith("/")) return false; // 相对路径或外链跳过
      const segs = staticSegments(t);
      if (segs.length === 0) return false;
      return !declared.some((d) => d.every((s) => t.includes(s)));
    });
    expect(orphans, `以下入口指向未声明的路由：${orphans.join(", ")}`).toEqual([]);
  });
});

function segments_empty(segs: string[]): boolean {
  return segs.length === 0;
}
