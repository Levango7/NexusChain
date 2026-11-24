import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import type { AddressInfo } from "node:net";
import { createApp } from "../../backend/src/app.js";

let origin = "";
let closeBff: (() => Promise<void>) | null = null;

test.beforeAll(async () => {
  // 资金页不调用 core RPC；真实 BFF 只负责同源托管前端产物。
  const app = createApp({ rpcUrl: "http://127.0.0.1:9/rpc" });
  const server = app.listen(0, "127.0.0.1");
  await new Promise<void>((resolve) => server.once("listening", resolve));
  origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  closeBff = () => new Promise<void>((resolve) => server.close(() => resolve()));
});

test.afterAll(async () => {
  if (closeBff) await closeBff();
});

test("XSS 载荷只按文本渲染，不创建元素也不执行标记", async ({ page }) => {
  const attack = '<img src=x onerror="window.__xssExecuted=true">';
  const corsHeaders = {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "GET,OPTIONS",
    "Access-Control-Allow-Headers": "*",
  };
  await page.addInitScript(() => Object.assign(window, { __xssExecuted: false }));
  await page.route("http://localhost:8080/api/v1/fund-dashboard", async (route) => {
    if (route.request().method() === "OPTIONS") {
      await route.fulfill({ status: 200, headers: corsHeaders, body: "" });
      return;
    }
    const row = {
      txNo: "TX-XSS",
      operationType: attack,
      direction: "CREDIT",
      amount: "1",
      balanceBefore: "0",
      balanceAfter: "1",
      createdAt: "2026-10-24T10:00:00",
    };
    await route.fulfill({
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({
        fundOverview: { balance: "1", balanceStatus: "ACTIVE" },
        transactionTrend: [],
        recentTransactions: [row],
        anomalyMonitor: { hasAlert: true, anomalies: [row] },
        generatedAt: "2026-10-24T12:00:00",
      }),
    });
  });

  await page.goto(`${origin}/settings`);
  await page.locator("#api-key").fill(randomUUID().replace(/-/g, ""));
  await page.locator("#api-secret").fill(randomUUID().replace(/-/g, ""));
  await page.getByRole("button", { name: "保存凭证" }).click();
  await expect(page.getByText("凭证已保存")).toBeVisible();
  await page.goto(`${origin}/fund`);
  await expect(page.getByText(attack, { exact: true })).toHaveCount(2);
  await expect(page.locator('img[src="x"]')).toHaveCount(0);
  await page.waitForLoadState("networkidle");
  expect(await page.evaluate(() => Reflect.get(window, "__xssExecuted"))).toBe(false);
});
