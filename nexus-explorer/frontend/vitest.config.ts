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
        //
        // 2026-10-12 上调（合约页 / 跨链页补齐后实测 Stmts 64.83 / Branch 58.66 /
        // Funcs 64.70 / Lines 67.05）：取实测值向下取整再留 ~1 点余量，
        // 避免并行度/测试顺序造成的微小抖动误报。
        thresholds: {
          lines: 66,
          statements: 63,
          functions: 63,
          branches: 57,
        },
      },
    },
  }),
);
