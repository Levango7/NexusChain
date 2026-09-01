/**
 * NexusChain 地址本地校验（零依赖实现）。
 *
 * 语义对齐 Java KeystoreAction.verifyAddress：
 *   - Base58（Bitcoin 字母表）解码
 *   - 总长 25 字节 = 1 版本字节（0x00）+ 20 公钥哈希 + 4 校验尾
 *   - 校验尾 = keccak256(keccak256(pubkeyHash))[:4]，pubkeyHash = decoded[1:21]
 *
 * Keccak-256（原始 Keccak padding 0x01，非 NIST SHA3 的 0x06）以纯 TS 实现。
 */

const ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
const CHAR_INDEX: Record<string, number> = {};
for (let i = 0; i < ALPHABET.length; i++) CHAR_INDEX[ALPHABET[i]] = i;

/** Base58 解码（Bitcoin 字母表）。非法字符抛 Error。 */
export function base58Decode(s: string): Uint8Array {
  let zeros = 0;
  while (zeros < s.length && s[zeros] === '1') zeros++;

  // big-endian base-58 → BigInt（64 位安全）
  let n = 0n;
  for (const ch of s) {
    const v = CHAR_INDEX[ch];
    if (v === undefined) throw new Error(`invalid base58 character: ${ch}`);
    n = n * 58n + BigInt(v);
  }
  const bodyHex = n.toString(16).padStart((n.toString(16).length + 1) >> 1 << 1, '0');
  const body = n === 0n ? new Uint8Array(0) : hexToBytes(bodyHex);
  const out = new Uint8Array(zeros + body.length);
  out.set(body, zeros);
  return out;
}

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.length % 2 ? '0' + hex : hex;
  const out = new Uint8Array(clean.length >> 1);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(clean.substr(i * 2, 2), 16);
  }
  return out;
}

// ---------------- Keccak-256（原始 Keccak padding） ----------------

const ROUND_CONSTANTS = [
  0x0000000000000001n, 0x0000000000008082n, 0x800000000000808an,
  0x8000000080008000n, 0x000000000000808bn, 0x0000000080000001n,
  0x8000000080008081n, 0x8000000000008009n, 0x000000000000008an,
  0x0000000000000088n, 0x0000000080008009n, 0x000000008000000an,
  0x000000008000808bn, 0x800000000000008bn, 0x8000000000008089n,
  0x8000000000008003n, 0x8000000000008002n, 0x8000000000000080n,
  0x000000000000800an, 0x800000008000000an, 0x8000000080008081n,
  0x8000000000008080n, 0x0000000080000001n, 0x8000000080008008n,
];

const ROTATION_OFFSETS = [
  [0, 36, 3, 41, 18], [1, 44, 10, 45, 2], [62, 6, 43, 15, 61],
  [28, 55, 25, 21, 56], [27, 20, 39, 8, 14],
] as const;

const MASK = (1n << 64n) - 1n;

function rotl64(v: bigint, n: number): bigint {
  const s = BigInt(n % 64);
  return ((v << s) | (v >> (64n - s))) & MASK;
}

function keccakF1600(lanes: bigint[]): bigint[] {
  for (let round = 0; round < 24; round++) {
    // theta
    const c: bigint[] = [];
    for (let x = 0; x < 5; x++) {
      c[x] = lanes[x] ^ lanes[x + 5] ^ lanes[x + 10] ^ lanes[x + 15] ^ lanes[x + 20];
    }
    const d: bigint[] = [];
    for (let x = 0; x < 5; x++) {
      d[x] = c[(x + 4) % 5] ^ rotl64(c[(x + 1) % 5], 1);
    }
    for (let i = 0; i < 25; i++) lanes[i] ^= d[i % 5];
    // rho + pi（与 Go/Python SDK 同款直写式：读 lanes 原文，写 b 新表）
    const b = lanes.slice();
    let [x, y] = [1, 0];
    let r = 0;
    for (let t = 0; t < 24; t++) {
      const [nx, ny] = [y, (2 * x + 3 * y) % 5];
      r = (r + t + 1) % 64;
      b[nx + 5 * ny] = rotl64(lanes[x + 5 * y], r);
      [x, y] = [nx, ny];
    }
    b[0] = lanes[0];
    for (let i = 0; i < 25; i++) lanes[i] = b[i];
    // chi（逐行）
    for (let row = 0; row < 5; row++) {
      const base = row * 5;
      const v0 = lanes[base], v1 = lanes[base + 1], v2 = lanes[base + 2],
        v3 = lanes[base + 3], v4 = lanes[base + 4];
      lanes[base] = v0 ^ (~v1 & v2 & MASK);
      lanes[base + 1] = v1 ^ (~v2 & v3 & MASK);
      lanes[base + 2] = v2 ^ (~v3 & v4 & MASK);
      lanes[base + 3] = v3 ^ (~v4 & v0 & MASK);
      lanes[base + 4] = v4 ^ (~v0 & v1 & MASK);
    }
    // iota
    lanes[0] ^= ROUND_CONSTANTS[round];
  }
  return lanes;
}

/** Keccak-256（原始 Keccak padding 0x01...0x80，率 136 字节）。 */
export function keccak256(data: Uint8Array): Uint8Array {
  const rate = 136;
  const lanes: bigint[] = new Array(25).fill(0n);

  const padded = new Uint8Array(
    Math.ceil((data.length + 1) / rate) * rate,
  );
  padded.set(data);
  padded[data.length] = 0x01; // Keccak padding（非 NIST 的 0x06）
  padded[padded.length - 1] |= 0x80;

  for (let off = 0; off < padded.length; off += rate) {
    for (let i = 0; i < rate / 8; i++) {
      let lane = 0n;
      for (let j = 7; j >= 0; j--) {
        lane = (lane << 8n) | BigInt(padded[off + i * 8 + j]);
      }
      lanes[i] ^= lane;
    }
    keccakF1600(lanes);
  }

  const out = new Uint8Array(32);
  for (let i = 0; i < 4; i++) {
    let lane = lanes[i];
    for (let j = 0; j < 8; j++) {
      out[i * 8 + j] = Number(lane & 0xffn);
      lane >>= 8n;
    }
  }
  return out;
}

/** 本地校验 NexusChain 地址（Base58 + 25 字节 + keccak 双哈希校验尾）。 */
export function validateAddress(address: string): boolean {
  let decoded: Uint8Array;
  try {
    decoded = base58Decode(address);
  } catch {
    return false;
  }
  if (decoded.length !== 25) return false;
  const pubkeyHash = decoded.slice(1, 21);
  const checksum = decoded.slice(21, 25);
  const h = keccak256(keccak256(pubkeyHash));
  return h[0] === checksum[0] && h[1] === checksum[1] &&
    h[2] === checksum[2] && h[3] === checksum[3];
}
