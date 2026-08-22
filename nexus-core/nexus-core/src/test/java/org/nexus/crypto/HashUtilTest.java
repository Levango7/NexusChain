package org.nexus.crypto;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HashUtil} 回归测试。
 *
 * <p>覆盖重点：
 * <ul>
 *   <li>P2-11 fail-fast 改动：哈希计算失败时抛 {@link IllegalStateException}，
 *       不再返回 {@code null}（v2.27.0 第三轮安全审计）。</li>
 *   <li>正常输入返回正确摘要、空数组不抛异常、确定性与抗碰撞性。</li>
 *   <li>各哈希算法（sha256/keccak256/sha3/sha512/ripemd160 等）返回长度正确。</li>
 * </ul>
 */
class HashUtilTest {

    // SHA-256("") 已知摘要
    private static final String SHA256_EMPTY_HEX =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    // SHA-256("abc") NIST FIPS 180-4 B.1 标准向量
    private static final String SHA256_ABC_HEX =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    @DisplayName("sha256: 正常输入返回正确的 SHA-256 哈希")
    void sha256NormalInput() {
        byte[] hash = HashUtil.sha256("abc".getBytes());
        assertEquals(32, hash.length);
        assertEquals(SHA256_ABC_HEX, Hex.toHexString(hash));
    }

    @Test
    @DisplayName("sha256: 空数组返回正确的哈希（不抛异常）")
    void sha256EmptyArray() {
        byte[] hash = HashUtil.sha256(new byte[0]);
        assertEquals(32, hash.length);
        assertEquals(SHA256_EMPTY_HEX, Hex.toHexString(hash));
    }

    @Test
    @DisplayName("sha256: null 输入 fail-fast 抛 IllegalStateException（P2-11，不再返回 null）")
    void sha256NullThrowsFailFast() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> HashUtil.sha256(null));
        assertTrue(ex.getMessage().contains("SHA-256"),
                "异常消息应包含算法名");
        // 底层 NPE 作为 cause 保留，证明 null 被直接传入而非静默吞掉
        assertInstanceOf(NullPointerException.class, ex.getCause());
    }

    @Test
    @DisplayName("sha256: 相同输入返回相同哈希（确定性）")
    void sha256Deterministic() {
        byte[] input = "deterministic".getBytes();
        assertArrayEquals(HashUtil.sha256(input), HashUtil.sha256(input));
    }

    @Test
    @DisplayName("sha256: 不同输入返回不同哈希")
    void sha256DifferentInputsProduceDifferentHashes() {
        byte[] h1 = HashUtil.sha256("input1".getBytes());
        byte[] h2 = HashUtil.sha256("input2".getBytes());
        assertFalse(java.util.Arrays.equals(h1, h2));
    }

    @Test
    @DisplayName("keccak256: null 输入 fail-fast 抛 IllegalStateException")
    void keccak256NullThrowsFailFast() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> HashUtil.keccak256(null));
        assertTrue(ex.getMessage().contains("KECCAK-256"));
        assertInstanceOf(NullPointerException.class, ex.getCause());
    }

    @Test
    @DisplayName("keccak256: 正常输入返回 32 字节哈希")
    void keccak256NormalInput() {
        byte[] hash = HashUtil.keccak256("test".getBytes());
        assertEquals(32, hash.length);
    }

    @Test
    @DisplayName("sha3: 返回 32 字节哈希")
    void sha3Returns32Bytes() {
        assertEquals(32, HashUtil.sha3("data".getBytes()).length);
    }

    @Test
    @DisplayName("sha3(a,b): 等价于 sha3(a||b) 拼接")
    void sha3TwoArgsConcatenatesInputs() {
        byte[] a = "foo".getBytes();
        byte[] b = "bar".getBytes();
        assertArrayEquals(HashUtil.sha3(a, b), HashUtil.sha3("foobar".getBytes()));
    }

    @Test
    @DisplayName("sha512: 返回 64 字节哈希")
    void sha512Returns64Bytes() {
        assertEquals(64, HashUtil.sha512("data".getBytes()).length);
    }

    @Test
    @DisplayName("ripemd160: 返回 20 字节哈希")
    void ripemd160Returns20Bytes() {
        assertEquals(20, HashUtil.ripemd160("data".getBytes()).length);
    }

    @Test
    @DisplayName("sha3omit12: 返回 20 字节（截取 keccak256 的右 20 字节，用于地址计算）")
    void sha3omit12Returns20Bytes() {
        assertEquals(20, HashUtil.sha3omit12("addr".getBytes()).length);
    }

    @Test
    @DisplayName("doubleDigest: 确定性且返回 32 字节（Bitcoin 双 SHA-256）")
    void doubleDigestDeterministic() {
        byte[] input = "bitcoin".getBytes();
        byte[] h1 = HashUtil.doubleDigest(input);
        byte[] h2 = HashUtil.doubleDigest(input);
        assertEquals(32, h1.length);
        assertArrayEquals(h1, h2);
    }

    @Test
    @DisplayName("randomHash: 返回 32 字节，两次调用不同（SecureRandom 熵源）")
    void randomHashReturns32BytesAndUnique() {
        byte[] h1 = HashUtil.randomHash();
        byte[] h2 = HashUtil.randomHash();
        assertEquals(32, h1.length);
        assertEquals(32, h2.length);
        assertFalse(java.util.Arrays.equals(h1, h2),
                "SecureRandom 两次输出不应相同");
    }

    @Test
    @DisplayName("shortHash: 返回 6 个十六进制字符")
    void shortHashReturns6HexChars() {
        byte[] hash = HashUtil.sha256("short".getBytes());
        String sh = HashUtil.shortHash(hash);
        assertEquals(6, sh.length());
    }

    @Test
    @DisplayName("calcSaltAddr: 返回 20 字节 CREATE2 地址")
    void calcSaltAddrReturns20Bytes() {
        byte[] sender = new byte[20];
        byte[] initCode = "init".getBytes();
        byte[] salt = new byte[32];
        byte[] addr = HashUtil.calcSaltAddr(sender, initCode, salt);
        assertEquals(20, addr.length);
    }

    @Test
    @DisplayName("EMPTY_DATA_HASH: 等于 sha3(空数组)，静态初始化正确")
    void emptyDataHashConstantMatchesSha3OfEmpty() {
        assertEquals(32, HashUtil.EMPTY_DATA_HASH.length);
        assertArrayEquals(HashUtil.EMPTY_DATA_HASH, HashUtil.sha3(new byte[0]));
    }

    @Test
    @DisplayName("whirlPool: 返回 64 字节哈希")
    void whirlPoolReturns64Bytes() {
        assertEquals(64, HashUtil.whirlPool("data".getBytes()).length);
    }

    @Test
    @DisplayName("blake2b256: 返回 32 字节哈希")
    void blake2b256Returns32Bytes() {
        assertEquals(32, HashUtil.blake2b256("data".getBytes()).length);
    }

    @Test
    @DisplayName("sha3256: 返回 32 字节哈希")
    void sha3256Returns32Bytes() {
        assertEquals(32, HashUtil.sha3256("data".getBytes()).length);
    }

    @Test
    @DisplayName("ripemd256: 返回 32 字节哈希")
    void ripemd256Returns32Bytes() {
        assertEquals(32, HashUtil.ripemd256("data".getBytes()).length);
    }

    @Test
    @DisplayName("skein256256: 返回 32 字节哈希")
    void skein256256Returns32Bytes() {
        assertEquals(32, HashUtil.skein256256("data".getBytes()).length);
    }
}