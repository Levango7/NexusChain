package org.nexus.crypto;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AES256CTR 测试——CTR 模式往返、长度保持、IV 敏感性、模式名常量。
 */
class AES256CTRTest {

    private static final byte[] KEY = new byte[32];
    private static final byte[] IV = new byte[16];

    static {
        new SecureRandom().nextBytes(KEY);
        new SecureRandom().nextBytes(IV);
    }

    @Test
    void cipherNameConstantExposed() {
        assertEquals("aes-256-ctr", AES256CTR.getCipher(), "模式名常量应为 aes-256-ctr");
    }

    @Test
    void encryptDecryptRoundTripAndLengthPreserved() throws Exception {
        AES256CTR codec = new AES256CTR(IV);
        byte[] data = new byte[0];
        assertArrayEquals(data, codec.decrypt(KEY, codec.encrypt(KEY, data)),
            "空数据往返");

        data = "NexusChain-AES-CTR-payload-é".getBytes();
        byte[] ct = codec.encrypt(KEY, data);
        assertEquals(data.length, ct.length, "CTR 模式密文长度等于明文");
        assertFalse(Arrays.equals(data, ct), "加密输出应不同于明文");
        assertArrayEquals(data, codec.decrypt(KEY, ct), "解密应还原明文");
    }

    @Test
    void differentIvOrKeyProduceDifferentCiphertext() throws Exception {
        byte[] data = "same-plaintext".getBytes();
        byte[] ct1 = new AES256CTR(IV).encrypt(KEY, data);

        byte[] iv2 = IV.clone();
        iv2[0] ^= 0x01;
        byte[] ct2 = new AES256CTR(iv2).encrypt(KEY, data);
        assertFalse(Arrays.equals(ct1, ct2), "不同 IV 应产生不同密文");

        byte[] key2 = KEY.clone();
        key2[0] ^= 0x01;
        byte[] ct3 = new AES256CTR(IV).encrypt(key2, data);
        assertFalse(Arrays.equals(ct1, ct3), "不同密钥应产生不同密文");
    }

    @Test
    void sameIvAndKeyAreDeterministic() throws Exception {
        byte[] data = "deterministic".getBytes();
        assertArrayEquals(new AES256CTR(IV).encrypt(KEY, data),
            new AES256CTR(IV).encrypt(KEY, data),
            "CTR 为流密码，同 IV+Key 加密结果确定");
    }
}