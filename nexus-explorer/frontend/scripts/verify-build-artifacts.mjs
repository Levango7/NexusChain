#!/usr/bin/env node
/**
 * 构建产物完整性校验（回归守卫）
 *
 * 背景（2026-09-16 审查修复）：
 *   源码里写了正确的 Tailwind 类名，**不代表**构建产物里有对应的 CSS 规则。
 *   以下三类缺陷都无法被 ESLint / Vitest 发现，只有校验产物才能捕获：
 *     1. 设计令牌 CSS 未注入 —— @import 位置非法被构建器丢弃，
 *        所有 var(--x) 解析失败，全站配色与主题失效
 *     2. 透明度修饰符被静默丢弃 —— 颜色写成 `var(--x)` 时 Tailwind 不做 alpha
 *        合成，`bg-surface/80` 这类类不生成
 *     3. 主题映射遗漏 —— config 的 theme.extend 少映射一类（如 zIndex），
 *        对应工具类（`z-sticky`）全部静默失效
 *
 * 用法：npm run build && node scripts/verify-build-artifacts.mjs
 * 退出码：0 = 全部通过；1 = 存在未生成的关键类或缺失的令牌
 */

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const assetsDir = join(root, "dist", "assets");

/* ---------- 1. 定位产物 CSS ---------- */
if (!existsSync(assetsDir)) {
  console.error("✗ 未找到 dist/assets —— 请先执行 npm run build");
  process.exit(1);
}
const cssName = readdirSync(assetsDir).find((f) => f.endsWith(".css"));
if (!cssName) {
  console.error("✗ dist/assets 下无 CSS 产物");
  process.exit(1);
}
const css = readFileSync(join(assetsDir, cssName), "utf8");
console.log(`产物 CSS: ${cssName} (${css.length} bytes)\n`);

let failures = 0;
const report = (ok, label, detail = "") => {
  if (!ok) failures++;
  console.log(`${ok ? "  ok  " : " FAIL "}${label}${detail ? "  " + detail : ""}`);
};

/* ---------- 2. 设计令牌必须全部注入（缺陷类型 1） ---------- */
console.log("── 设计令牌注入 ──");

/** 随主题变化的令牌：必须同时存在于暗色 :root 与 [data-theme=light] 覆盖块中 */
const THEMED_TOKENS = [
  "bg", "surface", "surface-2", "border", "border-soft", "border-strong",
  "fg", "fg-2", "muted",
  "accent", "accent-solid", "accent-solid-hover", "accent-solid-active", "accent-on", "accent-soft",
  "success", "success-soft", "warn", "warn-soft",
  "danger", "danger-soft", "danger-solid", "danger-on",
];
/** 主题无关的令牌：只在 :root 定义一次 */
const SHARED_TOKENS = [
  "font-display", "font-body", "font-mono",
  "radius-sm", "radius-md", "radius-lg", "radius-pill",
  "elev-flat", "elev-ring", "elev-raised",
  "focus-ring", "motion-fast", "motion-base", "ease-standard", "tracking-caps",
];

for (const t of THEMED_TOKENS) {
  const n = css.split(`--${t}:`).length - 1;
  report(n >= 2, `--${t}:`, `x${n}（期望 ≥2：暗色 + 亮色）`);
}
for (const t of SHARED_TOKENS) {
  const n = css.split(`--${t}:`).length - 1;
  report(n >= 1, `--${t}:`, `x${n}（期望 ≥1）`);
}
// RGB 通道变量（供 Tailwind alpha 使用），随主题变化
for (const t of ["bg", "surface", "accent", "accent-solid", "fg", "muted", "success", "warn", "danger"]) {
  const n = css.split(`--${t}-rgb:`).length - 1;
  report(n >= 2, `--${t}-rgb:`, `x${n}`);
}
// 亮色主题块必须存在
report(/\[data-theme=["']?light["']?\]/.test(css), "[data-theme=light] 主题块");

/* ---------- 3. 源码中实际使用的类必须生成（缺陷类型 2、3） ---------- */
console.log("\n── 源码类名 → 产物规则 ──");

/** 收集 src 下所有源文件 */
function collectSources(dir, out = []) {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) collectSources(p, out);
    else if (/\.(tsx|ts)$/.test(e.name) && !/\.test\./.test(e.name)) out.push(p);
  }
  return out;
}

/** 从 className / cls 字符串中提取候选类名 */
function extractClasses(src) {
  const found = new Set();
  // 匹配 className="..." / className={`...`} / "..." 中的类 token
  const stringLits = src.match(/(["'`])(?:[^"'`\\]|\\.)*?\1/g) ?? [];
  for (const lit of stringLits) {
    const body = lit.slice(1, -1);
    if (body.includes("${")) continue; // 跳过模板插值，无法静态解析
    for (const tok of body.split(/\s+/)) {
      if (/^[a-z][a-z0-9:./[\]%_-]*$/.test(tok) && /[:./]/.test(tok) === false) {
        // 单词类（如 flex / items-center）由后续白名单过滤
        found.add(tok);
      } else if (/^[a-z]/.test(tok) && tok.length > 2) {
        found.add(tok);
      }
    }
  }
  return found;
}

/**
 * 只校验「设计令牌类」——即以已知 token 词为后缀的类。
 * 通用工具类（flex / px-4 / text-sm）由 Tailwind 自身保证，不在本守卫范围，
 * 避免因动态拼接或变体写法产生误报。
 */
const TOKEN_SUFFIXES = [
  "bg", "surface", "surface-2", "border", "border-soft", "border-strong",
  "fg", "fg-2", "muted",
  "accent", "accent-solid", "accent-solid-hover", "accent-solid-active",
  "accent-on", "accent-soft",
  "success", "success-soft", "warn", "warn-soft",
  "danger", "danger-soft", "danger-solid", "danger-on",
];
const TOKEN_CLASS_RE = new RegExp(
  `^(?:hover:|focus:|focus-visible:|active:|disabled:|group-hover:|placeholder:)*` +
  `(?:bg|text|border|ring|fill|stroke|from|to|via)-(${TOKEN_SUFFIXES.join("|")})(?:/(?:\\d{1,3}))?$`
);
const OTHER_TOKEN_CLASS_RE = /^(?:z|shadow|ease|duration|rounded|tracking)-(base|sticky|dropdown|modal|toast|focus|raised|ring|standard|fast|sm|md|lg|full|pill|caps)$/;

const sources = collectSources(join(root, "src"));
const used = new Set();
for (const f of sources) for (const c of extractClasses(readFileSync(f, "utf8"))) used.add(c);

const candidates = [...used].filter(
  (c) => TOKEN_CLASS_RE.test(c) || OTHER_TOKEN_CLASS_RE.test(c.replace(/^(hover|focus|active|disabled|focus-visible):/, ""))
);

/** Tailwind 在 CSS 中转义 `:` `/` `[` `]` `%` 等字符（输出为反斜杠 + 原字符两个字符） */
const escapeForCss = (cls) => "." + cls.replace(/([:./[\]%])/g, "\\$1");

/**
 * 提取某选择器的规则体。
 * 注意：不要用 `new RegExp(sel + "\\{...\\}")` —— sel 中的转义反斜杠在正则里
 * 会被再次解释，导致匹配失败（CSS 里是「反斜杠 + 斜杠」两个字符，正则 `\/` 只匹配一个）。
 * 用 indexOf 定位再取到下一个 `}` 更可靠。
 */
function ruleBody(selector) {
  // 精确匹配：选择器后必须紧跟 `{`（无变体）或 `:`（伪类变体，如 .focus\:x:focus{）
  // 避免 `.bg-danger` 误命中 `.bg-danger\/10` 这类前缀相同但更长的选择器
  let i = -1;
  while ((i = css.indexOf(selector, i + 1)) >= 0) {
    const next = css[i + selector.length];
    if (next === "{" || next === ":") {
      const open = css.indexOf("{", i);
      const end = css.indexOf("}", open);
      return open < 0 || end < 0 ? null : css.slice(open + 1, end);
    }
  }
  return null;
}

const missing = [];
for (const cls of candidates.sort()) {
  const sel = escapeForCss(cls);
  if (!css.includes(sel)) missing.push(cls);
}
console.log(`  扫描 ${sources.length} 个源文件，命中 ${candidates.length} 个令牌类`);
for (const cls of candidates.sort()) {
  report(!missing.includes(cls), cls);
}

/* ---------- 4. 透明度修饰符必须带 alpha 值（缺陷类型 2 的深层校验） ---------- */
console.log("\n── 透明度修饰符 alpha 合成 ──");
const alphaClasses = [...used].filter((c) => TOKEN_CLASS_RE.test(c) && c.includes("/"));
if (alphaClasses.length === 0) {
  console.log("  （源码中无带透明度修饰符的令牌类）");
}
for (const cls of alphaClasses.sort()) {
  const body = ruleBody(escapeForCss(cls));
  if (body === null) {
    report(false, cls, "规则未生成");
    continue;
  }
  // 期望形如 rgb(var(--x-rgb) / .8)；若 alpha 被丢弃则只剩 var(--x) 或 rgb(... / 1)
  const ok = /\/\s*\.?\d/.test(body) && /-rgb\)/.test(body);
  report(ok, cls, ok ? "" : `alpha 未生效 → ${body}`);
}

/* ---------- 汇总 ---------- */
console.log("\n" + "=".repeat(46));
if (failures === 0) {
  console.log("构建产物校验全部通过");
  process.exit(0);
}
console.log(`构建产物校验未通过：${failures} 项`);
process.exit(1);
