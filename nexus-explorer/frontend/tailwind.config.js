/** @type {import('tailwindcss').Config} */
// 引用 tokens.ts 的主题映射，避免在 config 中重复硬编码颜色 / 圆角 / 阴影 / 层级。
//
// 修复记录（2026-09-16）：
//  1) 本文件与 tailwind.config.ts 曾并存。Tailwind v3 按 `.js → .cjs → .mjs → .ts`
//     顺序解析，`.ts` 永不加载，其独有的 transitionTimingFunction / ringColor 等
//     全部失效。现已删除 tailwind.config.ts，本文件为唯一配置。
//  2) 颜色改用 `rgb(var(--x-rgb) / <alpha-value>)` 通道形式，使 `bg-surface/80`
//     等透明度修饰符能正常生成（此前被 Tailwind 静默丢弃）。
//  3) 补全 zIndex / transitionTimingFunction / letterSpacing 映射，修复
//     `z-sticky`、`z-modal`、`ease-standard` 等 23 处工具类不生成的问题。
import { tailwindThemeExtend } from "./src/styles/tokens";

export default {
  // 主题机制是 `data-theme` 属性（见 tokens.css），而非 `.dark` 类。
  // `variant` 形式让 `dark:` 变体与 CSS 变量机制保持一致：
  // 无属性（默认暗色）与 data-theme="dark" 均匹配，data-theme="light" 不匹配。
  darkMode: [
    "variant",
    '&:where(:root:not([data-theme="light"]), :root:not([data-theme="light"]) *)',
  ],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      ...tailwindThemeExtend,
    },
  },
  plugins: [],
};
