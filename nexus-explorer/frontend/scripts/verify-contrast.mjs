#!/usr/bin/env node
/**
 * 设计令牌对比度校验（WCAG 2.1 AA 回归守卫）
 *
 * 直接解析 `src/styles/tokens.css` 的**实际值**并计算对比度，因此色值一旦被改动
 * 就会立即暴露 —— 而不是依赖某处硬编码的期望值。
 *
 * 覆盖场景（均取自组件中的真实用法）：
 *   · 文本 on 页面/容器：fg / fg-2 / muted / accent / success / warn / danger
 *     × bg / surface / surface-2
 *   · 实底按钮：accent-on on accent-solid(-hover/-active)、danger-on on danger-solid
 *   · Badge 浅底：text-{tone} on --{tone}-soft
 *   · 半透明 banner：bg-{tone}/10 叠 --bg 后的合成底
 *   · 可交互控件边界：border-strong on surface / bg（阈值 3:1）
 *
 * 用法：node scripts/verify-contrast.mjs
 * 退出码：0 = 全部达标；1 = 存在低于阈值的组合
 */

import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const css = readFileSync(join(root, "src", "styles", "tokens.css"), "utf8");

/* ---------- 解析 tokens.css ---------- */

/** 切出暗色块（:root 起）与亮色块（:root[data-theme='light'] 起）。 */
function splitThemes(source) {
  // 注意：必须匹配**选择器**形式 `:root[data-theme='light']`，不能只匹配
  // `[data-theme='light']` —— 文件头注释里也出现过该字面量，会把暗色块截断。
  const lightIdx = source.search(/:root\[data-theme=['"]light['"]\]/);
  if (lightIdx < 0) throw new Error("未在 tokens.css 中找到 :root[data-theme='light'] 选择器");
  return { dark: source.slice(0, lightIdx), light: source.slice(lightIdx) };
}

/** 提取 `--name: #RRGGBB;` 形式的颜色（忽略 rgba/color-mix 等派生值）。 */
function extractColors(block) {
  const out = {};
  for (const m of block.matchAll(/--([a-z0-9-]+)\s*:\s*(#[0-9a-fA-F]{6})\s*;/g)) {
    out[m[1]] = m[2].toLowerCase();
  }
  return out;
}

const { dark, light } = splitThemes(css);
const THEMES = [
  { name: "DARK", c: extractColors(dark) },
  { name: "LIGHT", c: extractColors(light) },
];

/* ---------- WCAG 计算 ---------- */

const hex2rgb = (h) => {
  const s = h.replace("#", "");
  return [
    parseInt(s.slice(0, 2), 16),
    parseInt(s.slice(2, 4), 16),
    parseInt(s.slice(4, 6), 16),
  ];
};
const lin = (v) => {
  const c = v / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
};
const luminance = (hex) => {
  const [r, g, b] = hex2rgb(hex).map(lin);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const ratio = (a, b) => {
  const l1 = luminance(a);
  const l2 = luminance(b);
  const hi = Math.max(l1, l2);
  const lo = Math.min(l1, l2);
  return (hi + 0.05) / (lo + 0.05);
};
const blend = (fg, bg, alpha) => {
  const f = hex2rgb(fg);
  const b = hex2rgb(bg);
  return (
    "#" +
    f
      .map((v, i) =>
        Math.round(v * alpha + b[i] * (1 - alpha))
          .toString(16)
          .padStart(2, "0"),
      )
      .join("")
  );
};

/* ---------- 断言 ---------- */

let failures = 0;
let checks = 0;

function check(label, fg, bg, min) {
  checks++;
  if (!fg || !bg) {
    failures++;
    console.log(` FAIL  ${label.padEnd(52)}缺少令牌（fg=${fg} bg=${bg}）`);
    return;
  }
  const r = ratio(fg, bg);
  const ok = r >= min;
  if (!ok) failures++;
  console.log(`${ok ? "  ok  " : " FAIL "}${label.padEnd(52)}${r.toFixed(2)}:1  (需 ≥${min})`);
}

const TEXT_BG = ["bg", "surface", "surface-2"];
const TEXT_FG = ["fg", "fg-2", "muted", "accent", "success", "warn", "danger"];

for (const { name, c } of THEMES) {
  console.log(`\n══ ${name} ══`);

  console.log("\n── 文本 on 页面/容器（≥4.5）──");
  for (const fg of TEXT_FG) {
    for (const bg of TEXT_BG) {
      check(`text-${fg} on ${bg}`, c[fg], c[bg], 4.5);
    }
  }

  console.log("\n── 实底按钮（≥4.5）──");
  check("accent-on on accent-solid", c["accent-on"], c["accent-solid"], 4.5);
  check("accent-on on accent-solid-hover", c["accent-on"], c["accent-solid-hover"], 4.5);
  check("accent-on on accent-solid-active", c["accent-on"], c["accent-solid-active"], 4.5);
  check("danger-on on danger-solid", c["danger-on"], c["danger-solid"], 4.5);

  console.log("\n── Badge 浅底（≥4.5）──");
  check("Badge primary: accent on accent-soft", c["accent"], c["accent-soft"], 4.5);
  check("Badge neutral: muted on surface-2", c["muted"], c["surface-2"], 4.5);
  for (const tone of ["success", "warn", "danger"]) {
    check(`Badge ${tone}: ${tone} on ${tone}-soft`, c[tone], c[`${tone}-soft`], 4.5);
  }

  console.log("\n── banner 半透明底（bg-x/10 叠 --bg，≥4.5）──");
  for (const tone of ["success", "warn", "danger"]) {
    const composed = blend(c[tone], c.bg, 0.1);
    check(`banner ${tone}: ${tone} on bg-${tone}/10`, c[tone], composed, 4.5);
  }

  console.log("\n── 可交互控件边界（≥3.0）──");
  check("border-strong on surface", c["border-strong"], c["surface"], 3.0);
  check("border-strong on bg", c["border-strong"], c["bg"], 3.0);
}

console.log("\n" + "=".repeat(50));
console.log(`共校验 ${checks} 组`);
if (failures === 0) {
  console.log("对比度校验全部通过");
  process.exit(0);
}
console.log(`对比度校验未通过：${failures} 组低于阈值`);
process.exit(1);
