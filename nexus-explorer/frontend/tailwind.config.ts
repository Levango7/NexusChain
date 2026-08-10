import type { Config } from "tailwindcss";

/**
 * NexusChain Explorer — Tailwind 配置（设计 Token 骨架）
 * 所有颜色/字体/圆角/阴影/动效均引用 src/styles/tokens.css 的 CSS 变量，
 * 组件内禁止再写 indigo-xxx / gray-xxx 工具类与硬编码 hex（除 #fff/#000）。
 *
 * 配套动作（前端落地 R5）：
 *  1) 安装 tailwindcss/postcss/autoprefixer（devDeps 已有 tailwindcss^3.4）
 *  2) 新增 postcss.config.js（plugins: tailwindcss, autoprefixer）
 *  3) main.tsx 顶部 import './styles/tokens.css'，删除 index.html 的 cdn.tailwindcss.com
 *  4) 包管理锁定 lucide-react@0.460.0
 */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        bg: "var(--bg)",
        surface: "var(--surface)",
        "surface-2": "var(--surface-2)",
        border: "var(--border)",
        "border-soft": "var(--border-soft)",
        fg: "var(--fg)",
        "fg-2": "var(--fg-2)",
        muted: "var(--muted)",
        accent: {
          DEFAULT: "var(--accent)",
          hover: "var(--accent-hover)",
          active: "var(--accent-active)",
          on: "var(--accent-on)",
          soft: "var(--accent-soft)",
        },
        success: "var(--success)",
        warn: "var(--warn)",
        danger: "var(--danger)",
      },
      fontFamily: {
        sans: "var(--font-body)",
        display: "var(--font-display)",
        mono: "var(--font-mono)",
      },
      borderRadius: {
        sm: "var(--radius-sm)",
        md: "var(--radius-md)",
        lg: "var(--radius-lg)",
        pill: "var(--radius-pill)",
      },
      boxShadow: {
        ring: "var(--elev-ring)",
        raised: "var(--elev-raised)",
      },
      transitionDuration: {
        fast: "150ms",
        base: "200ms",
      },
      transitionTimingFunction: {
        standard: "var(--ease-standard)",
      },
      ringColor: {
        focus: "var(--accent)",
      },
    },
  },
  plugins: [],
} satisfies Config;
