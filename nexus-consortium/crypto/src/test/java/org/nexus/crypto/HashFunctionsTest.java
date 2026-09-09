package org.nexus.crypto;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * HashFunctions 测试——keccak/sha3/ripemd 标准黄金向量 + 输出长度 + 通用 hash()。
 */
class HashFunctionsTest {

    @Test
    void keccak256MatchesStandardVectors() {
        assertArrayEquals(hex("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"),
            HashFunctions.keccak256(new byte[0]), "keccak256(\"\")（以太坊空 hash）不匹配");
        assertArrayEquals(hex("4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"),
            HashFunctions.keccak256("abc".getBytes()), "keccak256(\"abc\") 不匹配");
    }

    @Test
    void sha3MatchesStandardVectors() {
        assertArrayEquals(hex("a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a"),
            HashFunctions.sha3256(new byte[0]), "SHA3-256(\"\") 不匹配");
        assertArrayEquals(hex("3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"),
            HashFunctions.sha3256("abc".getBytes()), "SHA3-256(\"abc\") 不匹配");
    }

    @Test
    void ripemdMatchesStandardVectors() {
        assertArrayEquals(hex("cdf26213a150dc3ecb610f18f6b38b46"),
            HashFunctions.ripemd128(new byte[0]), "RIPEMD-128(\"\") 不匹配");
        assertArrayEquals(hex("8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"),
            HashFunctions.ripemd160("abc".getBytes()), "RIPEMD-160(\"abc\") 不匹配");
        assertArrayEquals(hex("02ba4c4e5f8ecd1877fc52d64d30e37a2d9774fb1e5d026380ae0168e3c5522d"),
            HashFunctions.ripemd256(new byte[0]), "RIPEMD-256(\"\") 不匹配");
        assertArrayEquals(hex("22d65d5661536cdc75c1fdf5c6de7b41b9f27325ebc61e8557177d705a0ec880151c3a32a00899b8"),
            HashFunctions.ripemd320(new byte[0]), "RIPEMD-320(\"\") 不匹配");
    }

    @Test
    void outputLengthsAreAsExpected() {
        assertEquals(64, HashFunctions.keccak512("x".getBytes()).length);
        assertEquals(32, HashFunctions.keccak256("x".getBytes()).length);
        assertEquals(32, HashFunctions.sha3256("x".getBytes()).length);
        assertEquals(16, HashFunctions.ripemd128("x".getBytes()).length);
        assertEquals(20, HashFunctions.ripemd160("x".getBytes()).length);
        assertEquals(32, HashFunctions.ripemd256("x".getBytes()).length);
        assertEquals(40, HashFunctions.ripemd320("x".getBytes()).length);
    }

    @Test
    void genericHashDelegatesToProvidedDigest() {
        Digest sm3 = new SM3Digest();
        byte[] in = "generic".getBytes();
        byte[] viaGeneric = HashFunctions.hash(in, sm3);
        assertEquals(32, viaGeneric.length);
        byte[] fresh = HashFunctions.hash(in, new SM3Digest());
        assertArrayEquals(viaGeneric, fresh, "不同 Digest 实例同输入应结果一致");
    }

    @Test
    void hashesAreDeterministicAndSensitiveToInput() {
        Random rnd = new Random(42);
        byte[] data = new byte[64];
        rnd.nextBytes(data);
        byte[] h1 = HashFunctions.keccak256(data);
        byte[] h2 = HashFunctions.keccak256(data);
        assertArrayEquals(h1, h2, "确定性");
        data[0] ^= 0x01;
        assertFalse(java.util.Arrays.equals(h1, HashFunctions.keccak256(data)),
            "输入翻转应改变杂凑");
    }

    private static byte[] hex(String s) {
        try {
            return Hex.decodeHex(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}