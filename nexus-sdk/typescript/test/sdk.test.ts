/**
 * NexusChain TS SDK 单测（v2.2.0 补真）。
 *
 * Node 内置 test runner（node --test，零第三方测试框架）+ 内联 JSON-RPC
 * mock（回 JsonRpcController 真实信封形状）。运行：
 *   cd nexus-sdk/typescript && npm test
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';
import * as http from 'node:http';
import { AddressInfo } from 'node:net';

import { NexusChainClient, validateAddress, keccak256, base58Decode } from '../src/index.js';

// ---------------- keccak/base58/address 权威向量 ----------------

test('keccak256 empty-string vector (Keccak, not NIST SHA3)', () => {
  const h = Buffer.from(keccak256(new Uint8Array(0))).toString('hex');
  assert.equal(h, 'c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470');
});

test('keccak256 abc vector', () => {
  const h = Buffer.from(keccak256(new TextEncoder().encode('abc'))).toString('hex');
  assert.equal(h.slice(0, 16), '4e03657aea45a94f');
});

test('base58Decode: golden Java address decodes to 25 bytes', () => {
  const decoded = base58Decode('1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk');
  assert.equal(decoded.length, 25, '1 version + 20 pubkey-hash + 4 checksum');
});

test('base58Decode rejects invalid characters', () => {
  assert.throws(() => base58Decode('0OIl'));
});

test('validateAddress rejects malformed inputs', () => {
  for (const bad of ['', 'abc', '0x1234', '1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLkX']) {
    assert.equal(validateAddress(bad), false, bad);
  }
});

test('validateAddress accepts self-consistent address and rejects tampering', () => {
  // 构造自洽地址：0x00 + 20 字节 hash + keccak²[:4]
  const pubkeyHash = Uint8Array.from({ length: 20 }, (_, i) => i + 1);
  const checksum = keccak256(keccak256(pubkeyHash)).slice(0, 4);
  const full = new Uint8Array(25);
  full.set(pubkeyHash, 1);
  full.set(checksum, 21);

  // base58 编码
  const alphabet = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
  let zeros = 0;
  for (const b of full) {
    if (b !== 0) break;
    zeros++;
  }
  let n = 0n;
  for (const b of full) n = (n << 8n) | BigInt(b);
  let digits = '';
  while (n > 0n) {
    digits = alphabet[Number(n % 58n)] + digits;
    n /= 58n;
  }
  const addr = '1'.repeat(zeros) + digits;

  assert.ok(validateAddress(addr), `self-consistent address must pass: ${addr}`);
  const tampered = (addr[0] === '2' ? '3' : '2') + addr.slice(1);
  assert.equal(validateAddress(tampered), false, 'tampered address must fail');
});

// ---------------- 信封解析（mock core） ----------------

function startFakeCore(): Promise<http.Server> {
  return new Promise((resolve) => {
    const srv = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        const rpc = JSON.parse(body);
        const resp: Record<string, unknown> = { jsonrpc: '2.0', id: rpc.id };
        switch (rpc.method) {
          case 'nexus_getBalance':
            resp.result = { balance: '123456789' };
            break;
          case 'nexus_getTransactionCount':
            resp.result = { count: 7 };
            break;
          case 'nexus_getNodeStatus':
            resp.result = {
              chainId: 31337, latestHeight: 100, latestHash: 'ab12',
              syncing: false, peers: 0, version: 'v2-rpc-bridge',
            };
            break;
          case 'nexus_getTransactionByHash':
            resp.result = { txHash: 'aa', status: 'success' };
            break;
          case 'nexus_getLatestTransactions':
            resp.result = [{ txHash: 'bb' }];
            break;
          case 'nexus_getTransactionsByAddress':
            resp.result = [{ txHash: 'aa' }];
            break;
          case 'nexus_getCrossChainTransactions':
            resp.result = [{ bridgeTxId: 'cc', status: 'confirmed' }];
            break;
          default:
            resp.error = { code: -32601, message: `method not found: ${rpc.method}` };
        }
        const out = JSON.stringify(resp);
        res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': out.length });
        res.end(out);
      });
    });
    srv.listen(0, '127.0.0.1', () => resolve(srv));
  });
}

test('envelope decoding against fake core', async () => {
  const srv = await startFakeCore();
  const { port } = srv.address() as AddressInfo;
  const client = new NexusChainClient({
    network: 'testnet',
    rpcUrl: `http://127.0.0.1:${port}/`,
  });

  try {
    // 余额：十进制字符串信封
    assert.equal(await client.wallet.getBalance('any'), '123456789');
    // nonce：count 信封
    assert.equal(await client.wallet.getNonce('any'), 7);
    // 节点状态数值字段
    assert.equal(await client.getBlockNumber(), 100);
    assert.equal(await client.getChainId(), 31337);
    // 交易查询
    const tx = await client.transaction.getTransactionByHash('aa');
    assert.equal(tx?.['status'], 'success');
    const latest = await client.transaction.getLatestTransactions(5);
    assert.equal(latest.length, 1);
    // 跨链桥
    const cc = await client.bridge.list(5);
    assert.equal(cc[0]['bridgeTxId'], 'cc');
    // 地址校验器接线
    assert.equal(client.wallet.validateAddress('bad!'), false);
    // buildTransfer 拒绝无效地址
    await assert.rejects(() => client.transaction.buildTransfer({ from: 'bad!', to: 'bad!', amount: '1' }));
    // submitTransfer 无 walletServiceUrl 时明确报错
    await assert.rejects(() => client.wallet.submitTransfer('a', 'b', '1'), /walletServiceUrl/);
  } finally {
    srv.close();
  }
});

test('unknown method surfaces RPC error', async () => {
  const srv = await startFakeCore();
  const { port } = srv.address() as AddressInfo;
  const client = new NexusChainClient({
    network: 'testnet',
    rpcUrl: `http://127.0.0.1:${port}/`,
  });
  try {
    await assert.rejects(() => client.rpc.call('nexus_nonexistent'), /method not found/);
  } finally {
    srv.close();
  }
});
