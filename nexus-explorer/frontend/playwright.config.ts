import { defineConfig } from "@playwright/test";

/**
 * 浏览器验收：默认使用本机 Chrome（channel=chrome），不额外下载浏览器。
 * 可用 PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH 覆盖为 CI 中的 Chromium 路径。
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 45_000,
  expect: { timeout: 8_000 },
  fullyParallel: false,
  workers: 1,
  reporter: "list",
  use: {
    baseURL: "http://127.0.0.1:3000",
    headless: true,
    // 本机优先用已安装的 Chrome；CI 用 `npx playwright install chromium` 自带的 Chromium。
    channel: process.env.CI ? "chromium" : "chrome",
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH,
    },
    viewport: { width: 1280, height: 800 },
    actionTimeout: 8_000,
    navigationTimeout: 20_000,
    screenshot: "only-on-failure",
  },
});
