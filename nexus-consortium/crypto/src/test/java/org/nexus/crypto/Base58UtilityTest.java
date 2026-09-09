package org.nexus.crypto;

import org.apache.commons.codec.binary.Hex;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base58 测试——比特币经典黄金向量、领零保留、往返与非法字符拒绝。
 */
class Base58UtilityTest {

    @Test
    void matchesBitcoinReferenceVectors() {
        assertEquals("", Base58Utility.encode(new byte[0]), "空输入编码为空串");
        assertEquals("1", Base58Utility.encode(new byte[]{0}), "单零字节 → '1'");
        assertEquals("11", Base58Utility.encode(new byte[]{0, 0}), "两个零字节 → '11'");
        assertEquals("2NEpo7TZRRrLZSi2U", Base58Utility.encode("Hello World!".getBytes()),
            "bitcoinj 黄金向量 'Hello World!'");
        assertEquals("11233QC4", Base58Utility.encode(hex("0000287fb4cd")),
            "黄金向量 0x0000287fb4cd（两个领零：'11' + '233QC4'）");
    }

    @Test
    void decodeMatchesBitcoinReferenceVectors() {
        assertArrayEquals(new byte[0], Base58Utility.decode(""));
        assertArrayEquals(new byte[]{0}, Base58Utility.decode("1"));
        assertArrayEquals(new byte[]{0, 0}, Base58Utility.decode("11"));
        assertArrayEquals("Hello World!".getBytes(), Base58Utility.decode("2NEpo7TZRRrLZSi2U"));
        assertArrayEquals(hex("0000287fb4cd"), Base58Utility.decode("11233QC4"),
            "解码应保留领零");
    }

    @Test
    void decodeToBigIntegerStripsLeadingZeros() {
        assertEquals(0, Base58Utility.decodeToBigInteger("").longValueExact());
        assertEquals(0x287fb4cdL, Base58Utility.decodeToBigInteger("11233QC4").longValueExact(),
            "bigint 解码忽略前导零");
        BigInteger expected = new BigInteger(1, new byte[]{0, 1, 2, 3});
        assertEquals(expected, Base58Utility.decodeToBigInteger(Base58Utility.encode(new byte[]{0, 1, 2, 3})));
    }

    @Test
    void roundTripPreservesLeadingZerosAndRandomData() {
        SecureRandom rnd = new SecureRandom();
        for (int len : new int[]{0, 1, 16, 33, 65}) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            assertArrayEquals(data, Base58Utility.decode(Base58Utility.encode(data)),
                "长度 " + len + " 往返失败");
        }
        assertArrayEquals(new byte[]{0, 0, 1, 0, 2},
            Base58Utility.decode(Base58Utility.encode(new byte[]{0, 0, 1, 0, 2})),
            "领零应保留");
    }

    @Test
    void rejectsCharactersOutsideAlphabet() {
        for (char c : new char[]{'0', 'I', 'O', 'l', ' ', '.'}) {
            assertThrows(RuntimeException.class, () -> Base58Utility.decode("2NE" + c + "o7"),
                "非法字符 '" + c + "' 应被拒绝");
        }
    }

    @Test
    void alphabetHasExpectedProperties() {
        assertEquals(58, Base58Utility.ALPHABET.length, "Base58 字母表 58 字符");
        assertTrue("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            .equals(new String(Base58Utility.ALPHABET)), "字母表顺序为标准 Base58");
    }

    private static byte[] hex(String s) {
        try {
            return Hex.decodeHex(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}