import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { createApp } from "../../backend/src/app.js";

/** 与 BFF 集成测试同一形态：真实 BFF + 可控 core RPC，不 mock 前端页面。 */
function startRpcMock() {
  const block = {
    height: 42,
    hash: "0xabc",
    parentHash: "0xdef",
    timestamp: 1_700_000_000,
    txCount: 0,
    proposer: "0xproposer",
    transactions: [],
  };
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    let body = "";
    req.on("data", (chunk) => (body += chunk));
    req.on("end", () => {
      const request = JSON.parse(body || "{}") as { method?: string; params?: unknown[] };
      let result: unknown;
      switch (request.method) {
        case "nexus_getLatestBlocks":
          result = [block];
          break;
        case "nexus_getBlockByHeight":
          result = request.params?.[0] === 42 ? block : null;
          break;
        case "nexus_getNodeStatus":
          result = {
            chainId: 1,
            latestHeight: 42,
            latestHash: "0xabc",
            syncing: false,
            peers: 3,
            version: "2.78.1",
          };
          break;
        case "nexus_getLatestTransactions":
          result = [
            {
              txHash: "0xt1",
              blockHeight: 42,
              from: "0xa",
              to: "0xb",
              amount: "1",
              status: "success",
              timestamp: 1,
            },
          ];
          break;
        case "nexus_getTransactionByHash":
          result = null;
          break;
        case "nexus_getBalance":
          result = { balance: "12345" };
          break;
        case "nexus_getTransactionCount":
          result = { count: 7 };
          break;
        case "nexus_getContractList":
          result = [];
          break;
        case "nexus_getCrossChainTransactions":
          result = [];
          break;
        default:
          result = null;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: 1, result }));
    });
  });
  return new Promise<string>((resolve) => {
    server.listen(0, "127.0.0.1", () =>
      resolve(`http://127.0.0.1:${(server.address() as AddressInfo).port}/rpc`),
    );
    server.unref();
  });
}

let closeBff: (() => Promise<void>) | null = null;

test.beforeAll(async () => {
  const rpcUrl = await startRpcMock();
  // BFF 默认仅放行 Vite 开发源 localhost:5173；本验收由 BFF 同源托管页面，
  // 必须显式声明该源，否则浏览器会拦掉页面→BFF 的 API 响应（真实 CORS 约束，不能 mock 掉）。
  process.env.CORS_ALLOWED_ORIGINS = "http://localhost:3000,http://127.0.0.1:3000";
  const app = createApp({ rpcUrl });
  // 不绑定单一 IPv4：本机 Chrome 对 localhost 可能走 ::1（IPv6），
  // 仅绑 127.0.0.1 会造成「页面能开、API 连不上」的假象。
  const server = app.listen(3000);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  closeBff = () => new Promise<void>((resolve) => server.close(() => resolve()));
});

test.afterAll(async () => {
  if (closeBff) await closeBff();
});

test("首页真渲染：core 数据落到页面，且没有 undefined 契约泄漏", async ({ page }) => {
  await page.goto("/");
  const firstBlock = page.locator("main section").first().locator("div[role=button]").first();
  await expect(firstBlock).toContainText("#42");
  await expect(firstBlock).toContainText("0xabc");
  const firstTx = page.locator("main section").nth(1).locator("div[role=button]").first();
  await expect(firstTx).toContainText("0xt1");
  await expect(page.locator("body")).not.toContainText("undefined");
});

test("桌面几何：主内容居中、双栏同顶同高、gap-6，无横向溢出", async ({ page }) => {
  await page.goto("/");
  const main = page.locator("main");
  const sections = main.locator("section");
  await expect(sections).toHaveCount(2);
  const mainBox = await main.boundingBox();
  expect(mainBox).not.toBeNull();
  // HomePage 明确使用 max-w-6xl mx-auto：1280 视口下应水平居中（±1px）。
  expect(Math.abs(mainBox!.x + mainBox!.width / 2 - 640)).toBeLessThanOrEqual(1);
  const a = await sections.nth(0).boundingBox();
  const b = await sections.nth(1).boundingBox();
  expect(Math.abs(a!.y - b!.y)).toBeLessThanOrEqual(1);
  expect(Math.abs(a!.height - b!.height)).toBeLessThanOrEqual(2);
  // Tailwind gap-6 = 24px；首栏右边到次栏左边的距离应恰为 24px（±1px）。
  expect(Math.abs(a!.x + a!.width + 24 - b!.x)).toBeLessThanOrEqual(1);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
  ).toBe(true);
});

test("移动视口：双栏退化为单栏，仍无横向溢出", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  const sections = page.locator("main section");
  const a = await sections.nth(0).boundingBox();
  const b = await sections.nth(1).boundingBox();
  expect(b!.y).toBeGreaterThanOrEqual(a!.y + a!.height - 1);
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
  ).toBe(true);
});

test("搜索联调：输入高度回车 → /block/42；块卡键盘 Enter 也可达", async ({ page }) => {
  await page.goto("/");
  await page.locator('input[type="text"]').fill("42");
  await page.locator('input[type="text"]').press("Enter");
  await expect(page).toHaveURL(/\/block\/42$/);
  await page.goto("/");
  const firstBlock = page.locator("main section").first().locator("div[role=button]").first();
  await firstBlock.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/block\/42$/);
});

test("资金看板：三张长表实际滚动后表头仍钉在各自容器顶部", async ({ page }) => {
  const rows = Array.from({ length: 30 }, (_, i) => ({
    txNo: `TX-${i}`,
    operationType: "PAYMENT_IN",
    direction: "CREDIT",
    amount: 1000 + i,
    balanceBefore: 11000 + i,
    balanceAfter: 12000 + i,
    reference: `ORD-${i}`,
    createdAt: "2026-10-24T10:00:00",
  }));
  const payload = {
    merchantId: 1,
    fundOverview: {
      balance: 12000,
      frozen: 0,
      reserve: 100,
      totalAssets: 12100,
      balanceStatus: "ACTIVE",
      alertFlag: null,
    },
    transactionTrend: Array.from({ length: 30 }, (_, i) => ({
      date: `2026-10-${String((i % 28) + 1).padStart(2, "0")}`,
      totalCredit: 1000 + i,
      totalDebit: 100,
      netFlow: 900 + i,
      creditCount: 2,
      debitCount: 1,
    })),
    recentTransactions: rows,
    anomalyMonitor: {
      hasAlert: true,
      alertLevel: "NEGATIVE_BALANCE",
      anomalyCount: rows.length,
      anomalies: rows.map((row) => ({ ...row, operationType: "REFUND" })),
    },
    generatedAt: "2026-10-24T12:00:00",
  };
  const corsHeaders = {
    "Access-Control-Allow-Origin": "http://127.0.0.1:3000",
    "Access-Control-Allow-Methods": "GET,OPTIONS",
    "Access-Control-Allow-Headers": "*",
  };
  await page.route("http://localhost:8080/api/v1/fund-dashboard", async (route) => {
    if (route.request().method() === "OPTIONS") {
      await route.fulfill({ status: 200, headers: corsHeaders, body: "" });
      return;
    }
    await route.fulfill({
      status: 200,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  });

  await page.goto("/settings");
  await page.locator("#api-key").fill(randomUUID().replace(/-/g, ""));
  await page.locator("#api-secret").fill(randomUUID().replace(/-/g, ""));
  await page.getByRole("button", { name: "保存凭证" }).click();
  await expect(page.getByText("凭证已保存")).toBeVisible();
  await page.goto("/fund");
  const tables = page.locator("table");
  await expect(tables).toHaveCount(3);

  for (let i = 0; i < 3; i++) {
    const container = tables.nth(i).locator("..");
    await container.evaluate((el) => {
      el.scrollTop = el.scrollHeight;
    });
    await expect.poll(() => container.evaluate((el) => el.scrollTop)).toBeGreaterThan(0);
    const containerBox = await container.boundingBox();
    const headerBox = await tables.nth(i).locator("thead th").first().boundingBox();
    expect(Math.abs(headerBox!.y - containerBox!.y)).toBeLessThanOrEqual(2);
  }
});
