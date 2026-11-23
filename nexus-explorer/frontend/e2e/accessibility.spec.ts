import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { createApp } from "../../backend/src/app.js";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "http://127.0.0.1:3000",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "*",
};

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
  const transaction = {
    txHash: "0xt1",
    blockHeight: 42,
    from: "0xa",
    to: "0xb",
    amount: "1",
    status: "success",
    timestamp: 1_700_000_000,
    data: null,
  };
  const contract = {
    address: "0xcontract",
    creator: "0xcreator",
    codeHash: "0xcode",
    wasmCode: "0061736d",
    createdAt: 1_700_000_000,
    abi: [],
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
        case "nexus_getTransactionsByAddress":
          result = [transaction];
          break;
        case "nexus_getTransactionByHash":
          result = request.params?.[0] === "0xt1" ? transaction : null;
          break;
        case "nexus_getBalance":
          result = { balance: "12345" };
          break;
        case "nexus_getTransactionCount":
          result = { count: 7 };
          break;
        case "nexus_getContractList":
          result = [contract];
          break;
        case "nexus_getContract":
          result = request.params?.[0] === contract.address ? contract : null;
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
  process.env.CORS_ALLOWED_ORIGINS = "http://localhost:3000,http://127.0.0.1:3000";
  const server = createApp({ rpcUrl }).listen(3000);
  await new Promise<void>((resolve) => server.once("listening", resolve));
  closeBff = () => new Promise<void>((resolve) => server.close(() => resolve()));
});

test.afterAll(async () => {
  if (closeBff) await closeBff();
});

async function expectNoViolations(page: Page, label: string) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  expect(
    result.violations.map((violation) => ({
      id: violation.id,
      impact: violation.impact,
      nodes: violation.nodes.map((node) => ({ target: node.target, summary: node.failureSummary })),
    })),
    label,
  ).toEqual([]);
}

async function saveTestCredentials(page: Page) {
  await page.goto("/settings");
  await page.locator("#api-key").fill(randomUUID().replace(/-/g, ""));
  await page.locator("#api-secret").fill(randomUUID().replace(/-/g, ""));
  await page.getByRole("button", { name: "保存凭证" }).click();
  await expect(page.getByText("凭证已保存")).toBeVisible();
}

test("axe WCAG A/AA：全部公开页面零违规", async ({ page }) => {
  test.setTimeout(120_000);
  const paths = [
    "/",
    "/block/42",
    "/tx/0xt1",
    "/address/0xa",
    "/contracts",
    "/contracts/0xcontract",
    "/crosschain",
    "/definitely-not-a-page",
  ];
  for (const path of paths) {
    await page.goto(path);
    await expect(page.locator("main")).toBeVisible();
    await expectNoViolations(page, `WCAG violations on ${path}`);
  }
  await page.goto("/settings");
  await expect(page.locator("main")).toBeVisible();
  await expectNoViolations(page, "WCAG violations on /settings");
});

test("axe WCAG A/AA：资金、编排、提现保护页数据态零违规", async ({ page }) => {
  test.setTimeout(120_000);
  await page.route("http://localhost:8080/**", async (route) => {
    const request = route.request();
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 200, headers: CORS_HEADERS, body: "" });
      return;
    }
    const url = new URL(request.url());
    let payload: unknown = {};
    if (url.pathname === "/api/v1/fund-dashboard") {
      payload = {
        merchantId: 1,
        fundOverview: {
          balance: 100,
          frozen: 0,
          reserve: 10,
          totalAssets: 110,
          balanceStatus: "ACTIVE",
          alertFlag: null,
        },
        transactionTrend: [],
        recentTransactions: [],
        anomalyMonitor: { hasAlert: false, alertLevel: null, anomalyCount: 0, anomalies: [] },
        generatedAt: "2026-10-24T12:00:00",
      };
    } else if (url.pathname === "/api/v1/payments" && url.search === "?limit=20") {
      payload = {
        data: [
          {
            id: "pay-a11y",
            amount: "1",
            currency: "NEX",
            status: "SUCCEEDED",
            connector: "chain",
            created_at: "2026-10-24T10:00:00",
          },
        ],
      };
    } else if (url.pathname === "/api/v1/payments/connectors") {
      payload = [
        {
          id: "connector-a11y",
          display_name: "Chain Sandbox",
          active: true,
          type: "chain",
          fee_bps: 25,
          currencies: ["NEX"],
        },
      ];
    } else if (url.pathname === "/api/v1/payments/routing-rules") {
      payload = [
        {
          id: "rule-a11y",
          name: "Priority Rule",
          strategy: "priority",
          connectors: ["connector-a11y"],
          priority: 1,
        },
      ];
    }
    await route.fulfill({
      status: 200,
      headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  });
  await saveTestCredentials(page);
  for (const path of ["/fund", "/orchestration"]) {
    await page.goto(path);
    await expect(page.locator("main")).toBeVisible();
    await expectNoViolations(page, `WCAG violations on ${path}`);
  }

  await page.route("http://localhost:8083/**", async (route) => {
    const request = route.request();
    if (request.method() === "OPTIONS") {
      await route.fulfill({ status: 200, headers: CORS_HEADERS, body: "" });
      return;
    }
    const url = new URL(request.url());
    let payload: unknown = [];
    if (url.pathname === "/api/v1/wallet/custody/balance") payload = { hot: "10", cold: "1000" };
    if (url.pathname === "/api/v1/wallet/whitelist/check") {
      payload = { address: "0xpayee", whitelisted: true, firstTimeDelay: false };
    }
    await route.fulfill({
      status: 200,
      headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
  });
  await page.goto("/wallet/approvals");
  await expect(page.getByText("连接托管钱包服务")).toBeVisible();
  await expectNoViolations(page, "WCAG violations on wallet connection form");
});

test("设置页亮色主题 axe WCAG A/AA 零违规", async ({ page }) => {
  await page.goto("/settings");
  await page.getByRole("button", { name: "亮色" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expectNoViolations(page, "WCAG violations on light settings");
  const darkThemeButton = page.getByRole("button", { name: "暗色" });
  await darkThemeButton.hover();
  await expectNoViolations(page, "WCAG violations on light settings ghost hover");
  const transitionProperty = await darkThemeButton.evaluate(
    (element) => getComputedStyle(element).transitionProperty,
  );
  expect(transitionProperty).not.toContain("color");
});
