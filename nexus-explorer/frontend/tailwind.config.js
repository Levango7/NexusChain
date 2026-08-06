/** @type {import('tailwindcss').Config} */
// 引用 tokens.ts 的主题映射，避免在 config 中重复硬编码颜色 / 圆角 / 阴影。
import { tailwindThemeExtend } from "./src/styles/tokens";

export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      ...tailwindThemeExtend,
      // 兼容旧引用（warn 别名）
      colors: {
        ...tailwindThemeExtend.colors,
        warning: tailwindThemeExtend.colors.warn,
      },
    },
  },
  plugins: [],
};
