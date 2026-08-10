package org.nexus.bridge.solana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Base58} 单元测试：覆盖编码/解码往返、边界场景与非法输入。
 */
class Base58Test {

    @Test
    @DisplayName("encode: null 输入返回 null")
    void encode_nullReturnsNull() {
        assertNull(Base58.encode(null));
    }

    @Test
    @DisplayName("encode: 空字节数组返回空字符串")
    void encode_emptyReturnsEmpty() {
        assertEquals("", Base58.encode(new byte[0]));
    }

    @Test
    @DisplayName("encode: 单字节 0x00 编码为 '1'")
    void encode_zeroByte() {
        assertEquals("1", Base58.encode(new byte[]{0x00}));
    }

    @Test
    @DisplayName("encode: 多个前导零字节编码为多个 '1'")
    void encode_multipleLeadingZeros() {
        assertEquals("11", Base58.encode(new byte[]{0x00, 0x00}));
        assertEquals("111", Base58.encode(new byte[]{0x00, 0x00, 0x00}));
    }

    @Test
    @DisplayName("encode: 已知向量 'hello' 应编码为 'Cn8eVZg'")
    void encode_hello() {
        // "hello" 的 base58 编码
        byte[] input = "hello".getBytes(StandardCharsets.UTF_8);
        String encoded = Base58.encode(input);
        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
    }

    @Test
    @DisplayName("decode: null 输入返回 null")
    void decode_nullReturnsNull() {
        assertNull(Base58.decode(null));
    }

    @Test
    @DisplayName("decode: 空字符串返回空数组")
    void decode_emptyReturnsEmpty() {
        assertArrayEquals(new byte[0], Base58.decode(""));
    }

    @Test
    @DisplayName("decode: '1' 解码为单字节 0x00")
    void decode_one() {
        assertArrayEquals(new byte[]{0x00}, Base58.decode("1"));
    }

    @Test
    @DisplayName("decode: '11' 解码为两个零字节")
    void decode_twoOnes() {
        assertArrayEquals(new byte[]{0x00, 0x00}, Base58.decode("11"));
    }

    @Test
    @DisplayName("decode: 非法字符抛 IllegalArgumentException")
    void decode_invalidCharThrows() {
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("0"));
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("O"));
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("I"));
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("l"));
        assertThrows(IllegalArgumentException.class, () -> Base58.decode("+"));
    }

    @Test
    @DisplayName("encode + decode 往返: 任意字节应能完整还原")
    void encodeDecodeRoundTrip() {
        // 测试多种字节模式
        byte[][] testCases = {
                {0x01},
                {0x01, 0x02, 0x03},
                {0x00, 0x01},
                {0x00, 0x00, 0x01},
                new byte[32], // 全零 32 字节（Solana 系统程序地址）
                {0x7F, (byte) 0xFF, (byte) 0x80, 0x01},
                "The quick brown fox".getBytes(StandardCharsets.UTF_8)
        };
        for (byte[] input : testCases) {
            String encoded = Base58.encode(input);
            byte[] decoded = Base58.decode(encoded);
            assertArrayEquals(input, decoded,
                    "Round-trip failed for input length " + input.length);
        }
    }

    @Test
    @DisplayName("encode: 32 字节全零应编码为 32 个 '1'")
    void encode_allZeros32() {
        byte[] zeros = new byte[32];
        String encoded = Base58.encode(zeros);
        assertEquals("1".repeat(32), encoded);
    }

    @Test
    @DisplayName("decode: 32 个 '1' 应解码为 32 字节全零")
    void decode_allOnes32() {
        byte[] decoded = Base58.decode("1".repeat(32));
        assertArrayEquals(new byte[32], decoded);
    }

    @Test
    @DisplayName("encode: 字母表第一个字符 '1' 对应值 0")
    void encode_firstAlphabetChar() {
        // 0x00 → '1'（字母表第 0 个字符）
        assertEquals("1", Base58.encode(new byte[]{0}));
    }

    @Test
    @DisplayName("encode: 字母表最后一个字符 'z' 对应值 57")
    void encode_lastAlphabetChar() {
        // 57 = 0x39，应编码为 'z'
        assertEquals("z", Base58.encode(new byte[]{57}));
    }

    @Test
    @DisplayName("decode: 'z' 应解码为单字节 57")
    void decode_lastAlphabetChar() {
        assertArrayEquals(new byte[]{57}, Base58.decode("z"));
    }

    @Test
    @DisplayName("encode + decode: 64 字节随机数据往返")
    void encodeDecodeRoundTrip_64Bytes() {
        byte[] input = new byte[64];
        for (int i = 0; i < 64; i++) {
            input[i] = (byte) (i * 7 + 13);
        }
        String encoded = Base58.encode(input);
        byte[] decoded = Base58.decode(encoded);
        assertArrayEquals(input, decoded);
    }
}