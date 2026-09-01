"""NexusChain 地址本地校验（零依赖实现）。

语义对齐 Java KeystoreAction.verifyAddress：
  - Base58（Bitcoin 字母表）解码
  - 总长 25 字节 = 1 版本字节（0x00）+ 20 公钥哈希 + 4 校验尾
  - 校验尾 = keccak256(keccak256(pubkeyHash))[:4]，其中
    pubkeyHash = decoded[1:21]（跳过版本字节）
  - Java 侧 addressToPubkeyHash 取 r5[0:21] 的 [1:21]——25 字节布局
    由黄金地址（KeystoreAction.main 的 1L3zk... 解码恰 25 字节）证实

Keccak-256（非 NIST SHA3-256）以纯 Python 实现（约 70 行，仅校验场景使用，
每次校验 ~0.5ms，地址校验不追求吞吐）。
"""

ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
_CHAR_INDEX = {c: i for i, c in enumerate(ALPHABET)}


def base58_decode(s: str) -> bytes:
    """Base58 解码（Bitcoin 字母表）。非法字符抛 ValueError。"""
    zeros = 0
    for ch in s:
        if ch != "1":
            break
        zeros += 1
    n = 0
    for ch in s:
        if ch not in _CHAR_INDEX:
            raise ValueError(f"invalid base58 character: {ch!r}")
        n = n * 58 + _CHAR_INDEX[ch]
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    return b"\x00" * zeros + body


# ---------------- Keccak-256（原始 Keccak padding，非 NIST SHA3） ----------------

_KECCAK_ROUND_CONSTANTS = [
    0x0000000000000001, 0x0000000000008082, 0x800000000000808A, 0x8000000080008000,
    0x000000000000808B, 0x0000000080000001, 0x8000000080008081, 0x8000000000008009,
    0x000000000000008A, 0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
    0x000000008000808B, 0x800000000000008B, 0x8000000000008089, 0x8000000000008003,
    0x8000000000008002, 0x8000000000000080, 0x000000000000800A, 0x800000008000000A,
    0x8000000080008081, 0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
]
_ROTATION_OFFSETS = [
    [0, 36, 3, 41, 18], [1, 44, 10, 45, 2], [62, 6, 43, 15, 61],
    [28, 55, 25, 21, 56], [27, 20, 39, 8, 14],
]


def _keccak_f1600(lanes: list) -> list:
    """Keccak-f[1600] 置换（24 轮，theta/rho+pi/chi/iota 展开式实现）。"""
    rc = _KECCAK_ROUND_CONSTANTS
    for rnd in range(24):
        # theta
        c = [lanes[x] ^ lanes[x + 5] ^ lanes[x + 10] ^ lanes[x + 15] ^ lanes[x + 20]
             for x in range(5)]
        d = [c[(x - 1) % 5] ^ _rotl(c[(x + 1) % 5], 1) for x in range(5)]
        lanes = [lanes[i] ^ d[i % 5] for i in range(25)]
        # rho + pi
        lanes = _rho_pi(lanes)
        # chi（逐行）
        for row in range(5):
            base = row * 5
            b0 = lanes[base]
            b1 = lanes[base + 1]
            b2 = lanes[base + 2]
            b3 = lanes[base + 3]
            b4 = lanes[base + 4]
            lanes[base] = b0 ^ ((~b1) & b2)
            lanes[base + 1] = b1 ^ ((~b2) & b3)
            lanes[base + 2] = b2 ^ ((~b3) & b4)
            lanes[base + 3] = b3 ^ ((~b4) & b0)
            lanes[base + 4] = b4 ^ ((~b0) & b1)
        # iota
        lanes[0] ^= rc[rnd]
    return lanes


def _rotl(v: int, n: int) -> int:
    n %= 64
    return ((v << n) | (v >> (64 - n))) & 0xFFFFFFFFFFFFFFFF


def _rho_pi(a: list) -> list:
    """ρ（旋转）+ π（位置置换）——标准 Keccak 展开式。"""
    b = [0] * 25
    x, y = 1, 0
    r = 0
    for t in range(24):
        nx, ny = y, (2 * x + 3 * y) % 5
        r = (r + t + 1) % 64
        idx_src = x + 5 * y
        idx_dst = nx + 5 * ny
        b[idx_dst] = _rotl(a[idx_src], r)
        x, y = nx, ny
    b[0] = a[0]
    return b


def keccak256(data: bytes) -> bytes:
    """Keccak-256（原始 Keccak padding 0x01...0x80，率 136）。"""
    rate = 136  # 1088 bits
    lanes = [0] * 25

    # padding: Keccak（非 SHA3）→ 0x01 起始，末字节 |= 0x80
    padded = bytearray(data)
    padded.append(0x01)
    while len(padded) % rate != 0:
        padded.append(0x00)
    padded[-1] |= 0x80

    for block_off in range(0, len(padded), rate):
        block = padded[block_off:block_off + rate]
        for i in range(rate // 8):
            lanes[i] ^= int.from_bytes(block[i * 8:(i + 1) * 8], "little")
        lanes = _keccak_f1600(lanes)

    out = b"".join(lanes[i].to_bytes(8, "little") for i in range(4))
    return out[:32]


def validate_address(address: str) -> bool:
    """本地校验 NexusChain 地址（不联网）。

    Base58 解码须为 25 字节（1 版本 + 20 哈希 + 4 校验尾），且
    keccak256(keccak256(pubkeyHash))[:4] == decoded[-4:]。
    """
    try:
        decoded = base58_decode(address)
    except ValueError:
        return False
    if len(decoded) != 25:
        return False
    pubkey_hash = decoded[1:21]
    checksum = decoded[-4:]
    return keccak256(keccak256(pubkey_hash))[:4] == checksum
