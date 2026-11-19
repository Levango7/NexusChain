import { test } from "node:test";
import assert from "node:assert/strict";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createApp, type CreateAppOptions } from "../app.js";

/** 一个最小但真实的 nexus-core JSON-RPC 节点：只返回数据，不实现业务逻辑。 */
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
        case "nexus_getBalance":
          result = { balance: "12345" };
          break;
        case "nexus_getTransactionCount":
          result = { count: 7 };
          break;
        case "nexus_getContractList":
          result = [
            {
              address: "0xc1",
              creator: "0xowner",
              codeHash: "0xcode",
              wasmCode: "00",
              createdAt: 1,
            },
          ];
          break;
        case "nexus_getContract":
          result = null;
          break;
        case "nexus_getCrossChainTransactions":
          result = [
            {
              txId: "x1",
              sourceChain: "a",
              targetChain: "b",
              amount: "1",
              status: "confirmed",
              timestamp: 1,
              from: "0xa",
              to: "0xb",
            },
          ];
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
        default:
          result = null;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ jsonrpc: "2.0", id: 1, result }));
    });
  });
  return new Promise<string>((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const port = (server.address() as AddressInfo).port;
      resolve(`http://127.0.0.1:${port}/rpc`);
    });
    // 测试之间不共享连接；调用方完成用例后关闭。
    server.unref();
  });
}

async function withBff<T>(
  options: CreateAppOptions,
  fn: (baseUrl: string) => Promise<T>,
): Promise<T> {
  const app = createApp(options);
  const server = app.listen(0, "127.0.0.1");
  await new Promise<void>((resolve) => server.once("listening", resolve));
  const baseUrl = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  try {
    return await fn(baseUrl);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

test("BFF 健康检查与节点状态：把 core RPC 的真实字段透传到 HTTP", async () => {
  const rpcUrl = await startRpcMock();
  await withBff({ rpcUrl }, async (baseUrl) => {
    const health = await fetch(`${baseUrl}/health`);
    assert.equal(health.status, 200);
    const status = (await (await fetch(`${baseUrl}/api/node/status`)).json()) as {
      latestHeight: number;
      latestHash: string;
      peers: number;
      version: string;
    };
    assert.equal(status.latestHeight, 42);
    assert.equal(status.latestHash, "0xabc");
    assert.equal(status.peers, 3);
    assert.equal(status.version, "2.78.1");
  });
});

test("区块详情：存在的块透传；不存在的块返回 404 + 可诊断 error", async () => {
  const rpcUrl = await startRpcMock();
  await withBff({ rpcUrl }, async (baseUrl) => {
    const found = await fetch(`${baseUrl}/api/blocks/42`);
    assert.equal(found.status, 200);
    assert.equal(((await found.json()) as { hash: string }).hash, "0xabc");
    const missing = await fetch(`${baseUrl}/api/blocks/999`);
    assert.equal(missing.status, 404);
    assert.equal(((await missing.json()) as { error: string }).error, "区块不存在");
    const invalid = await fetch(`${baseUrl}/api/blocks/not-a-height`);
    assert.equal(invalid.status, 400);
  });
});

test("地址接口：余额与交易计数来自 core 的两次 RPC 调用，而非前端 mock", async () => {
  const rpcUrl = await startRpcMock();
  await withBff({ rpcUrl }, async (baseUrl) => {
    const body = await (await fetch(`${baseUrl}/api/address/0xwallet`)).json();
    assert.deepEqual(body, { address: "0xwallet", balance: "12345", txCount: 7 });
  });
});

test("合约列表与 404：后端有/无数据的两种路径都被真实 HTTP 覆盖", async () => {
  const rpcUrl = await startRpcMock();
  await withBff({ rpcUrl }, async (baseUrl) => {
    const list = (await (await fetch(`${baseUrl}/api/contracts`)).json()) as Array<{
      codeHash: string;
    }>;
    assert.equal(list.length, 1);
    assert.equal(list[0].codeHash, "0xcode");
    const missing = await fetch(`${baseUrl}/api/contracts/0xmissing`);
    assert.equal(missing.status, 404);
    assert.equal(((await missing.json()) as { error: string }).error, "合约不存在");
  });
});

test("跨链与交易：真实 RPC 结果可被前端必需字段直接消费", async () => {
  const rpcUrl = await startRpcMock();
  await withBff({ rpcUrl }, async (baseUrl) => {
    const cross = (await (await fetch(`${baseUrl}/api/crosschain`)).json()) as Array<{
      txId: string;
      status: string;
    }>;
    assert.equal(cross[0].txId, "x1");
    assert.equal(cross[0].status, "confirmed");
    const tx = (await (await fetch(`${baseUrl}/api/tx/0xmissing`)).json()) as { error: string };
    assert.equal(tx.error, "交易不存在");
  });
});

test("前端静态托管：SPA 深链回落 index.html，API 与缺失静态资源不被吞成 200", async () => {
  const dir = await mkdtemp(join(tmpdir(), "nexus-bff-"));
  await writeFile(join(dir, "index.html"), "<html><body>explorer-e2e-marker</body></html>");
  try {
    const rpcUrl = await startRpcMock();
    await withBff({ rpcUrl, frontendDir: dir }, async (baseUrl) => {
      const deep = await fetch(`${baseUrl}/contracts/0xc1`);
      assert.equal(deep.status, 200);
      assert.match(await deep.text(), /explorer-e2e-marker/);
      const api404 = await fetch(`${baseUrl}/api/no-such-route`);
      assert.equal(api404.status, 404);
      const js404 = await fetch(`${baseUrl}/assets/missing.js`);
      assert.equal(js404.status, 404);
    });
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("SSE 链头：接入即收到 init，Content-Type 正确，断开后可关闭", async () => {
  const rpcUrl = await startRpcMock();
  await withBff({ rpcUrl }, async (baseUrl) => {
    const controller = new AbortController();
    const response = await fetch(`${baseUrl}/api/events/head`, { signal: controller.signal });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
    const reader = response.body!.getReader();
    const first = await reader.read();
    const text = new TextDecoder().decode(first.value);
    assert.match(text, /event: init/);
    controller.abort();
    await reader.cancel().catch(() => undefined);
  });
});
