import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";
import { NexusChainRpcClient } from "../rpc.js";

test("RPC token is forwarded from the environment and never fabricated", async () => {
  const observedTokens: Array<string | string[] | undefined> = [];
  const server = createServer((req, res) => {
    observedTokens.push(req.headers.token);
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        result: {
          chainId: 1,
          latestHeight: 7,
          latestHash: "0abc",
          syncing: false,
          peers: 0,
          version: "test",
        },
      }),
    );
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve());
  });

  const previousToken = process.env.NEXUS_RPC_TOKEN;
  const generatedToken = randomUUID();
  try {
    const address = server.address() as AddressInfo;
    const url = `http://127.0.0.1:${address.port}/rpc`;

    process.env.NEXUS_RPC_TOKEN = generatedToken;
    await new NexusChainRpcClient(url).getNodeStatus();
    await new NexusChainRpcClient(url, "").getNodeStatus();

    assert.deepEqual(observedTokens, [generatedToken, undefined]);
  } finally {
    if (previousToken === undefined) {
      delete process.env.NEXUS_RPC_TOKEN;
    } else {
      process.env.NEXUS_RPC_TOKEN = previousToken;
    }
    await new Promise<void>((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()));
    });
  }
});
