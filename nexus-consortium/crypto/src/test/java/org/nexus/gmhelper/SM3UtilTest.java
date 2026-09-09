package org.nexus.gmhelper;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SM3 杂凑测试——GM/T 0004-2012 标准黄金向量 + 往返/边界。
 */
class SM3UtilTest {

    @Test
    void hashMatchesStandardVectors() {
        // GM/T 0004-2012 附录标准向量
        assertArrayEquals(
                hex("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0"),
                SM3Util.hash("abc".getBytes()),
                "SM3(\"abc\") 黄金向量不匹配");
        assertArrayEquals(
                hex("1ab21d8355cfa17f8e61194831e81a8f22bec8c728fefb747ed035eb5082aa2b"),
                SM3Util.hash(new byte[0]),
                "SM3(\"\") 黄金向量不匹配");
    }

    @Test
    void hashProduces32BytesAndIsDeterministic() {
        byte[] in = "NexusChain-gm-test".getBytes();
        byte[] h1 = SM3Util.hash(in);
        byte[] h2 = SM3Util.hash(in);
        assertEquals(32, h1.length, "SM3 输出固定 32 字节");
        assertArrayEquals(h1, h2, "SM3 确定性");
        assertFalse(java.util.Arrays.equals(h1, in), "杂凑输出不应等于输入");
    }

    @Test
    void verifyAgreesWithHash() {
        byte[] in = "verify-me".getBytes();
        byte[] h = SM3Util.hash(in);
        assertTrue(SM3Util.verify(in, h), "同数据应验证通过");
        byte[] tampered = h.clone();
        tampered[0] ^= 0x01;
        assertFalse(SM3Util.verify(in, tampered), "篡改杂凑应验证失败");
    }

    @Test
    void hmacIsDeterministicAndNonEmpty() {
        byte[] key = "secret-key".getBytes();
        byte[] data = "payload".getBytes();
        byte[] mac1 = SM3Util.hmac(key, data);
        byte[] mac2 = SM3Util.hmac(key, data);
        assertArrayEquals(mac1, mac2, "HMAC 确定性");
        assertTrue(mac1.length > 0);
        assertFalse(java.util.Arrays.equals(mac1, SM3Util.hmac("other-key".getBytes(), data)),
                "不同密钥应产生不同 MAC");
    }

    private static byte[] hex(String s) {
        try {
            return Hex.decodeHex(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}