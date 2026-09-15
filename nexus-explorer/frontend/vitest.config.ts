import { defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: "jsdom",
      globals: true,
      include: ["src/**/__tests__/**/*.test.{ts,tsx}"],
      coverage: {
        provider: "v8",
        reporter: ["text", "lcov", "json-summary"],
        include: ["src/**/*.{ts,tsx}"],
        exclude: ["src/**/__tests__/**", "src/**/*.d.ts", "src/vite-env.d.ts", "src/main.tsx"],
        // 覆盖率基线（2026-09-16 审查新增）。
        // 数值取修复当轮实测值向下取整（Stmts 60.42 / Branch 52.01 /
        // Funcs 59.82 / Lines 62.59），仅用于防止下滑。
        // 提高覆盖率后请同步上调，不要下调。
        thresholds: {
          lines: 60,
          statements: 58,
          functions: 58,
          branches: 50,
        },
      },
    },
  }),
);
