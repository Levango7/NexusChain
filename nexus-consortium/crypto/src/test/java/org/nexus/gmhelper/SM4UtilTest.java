package org.nexus.gmhelper;

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SM4 测试——GM/T 0002-2012 黄金向量、四种模式往返、MAC（CMAC/GMAC/CBC-MAC）。
 */
class SM4UtilTest {

    // GM/T 0002-2012 附录 A.1 第一组 ECB 向量：明文 = 密钥
    private static final byte[] STD_KEY = hex("0123456789abcdeffedcba9876543210");
    private static final byte[] STD_CT = hex("681edf34d206965e86b3e94f536e4246");

    @Test
    void ecbNoPaddingMatchesStandardVector() throws Exception {
        assertArrayEquals(STD_CT, SM4Util.encrypt_Ecb_NoPadding(STD_KEY, STD_KEY.clone()),
            "GM/T 0002 标准向量不匹配");
        assertArrayEquals(STD_KEY, SM4Util.decrypt_Ecb_NoPadding(STD_KEY, STD_CT),
            "标准向量解密应还原明文");
    }

    @Test
    void ecbPaddingRoundTrip() throws Exception {
        byte[] data = "padding-éçü-数据".getBytes();
        byte[] ct = SM4Util.encrypt_Ecb_Padding(STD_KEY, data);
        assertTrue(ct.length % 16 == 0, "PKCS5 填充后应为 16 倍数");
        assertArrayEquals(data, SM4Util.decrypt_Ecb_Padding(STD_KEY, ct),
            "ECB-Padding 解密应还原明文");
    }

    @Test
    void cbcPaddingRoundTrip() throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        byte[] data = "cbc-padding".getBytes();
        byte[] ct = SM4Util.encrypt_Cbc_Padding(STD_KEY, iv, data);
        assertArrayEquals(data, SM4Util.decrypt_Cbc_Padding(STD_KEY, iv, ct),
            "CBC-Padding 解密应还原明文");

        // IV 不同则密文不同（CBC 特性）
        byte[] iv2 = iv.clone();
        iv2[0] ^= 0x01;
        assertFalse(Arrays.equals(ct, SM4Util.encrypt_Cbc_Padding(STD_KEY, iv2, data)),
            "不同 IV 应产生不同密文");
    }

    @Test
    void cbcNoPaddingRoundTrip() throws Exception {
        byte[] iv = "0123456789abcdef".getBytes();
        byte[] data = "0123456789abcdef".getBytes(); // 16 字节，免填充
        byte[] ct = SM4Util.encrypt_Cbc_NoPadding(STD_KEY, iv, data);
        assertEquals(16, ct.length, "NoPadding 密文长度应等于明文");
        assertArrayEquals(data, SM4Util.decrypt_Cbc_NoPadding(STD_KEY, iv, ct),
            "CBC-NoPadding 解密应还原明文");
    }

    @Test
    void generatedKeyIsRandomAnd128Bits() throws Exception {
        byte[] k1 = SM4Util.generateKey();
        byte[] k2 = SM4Util.generateKey();
        assertEquals(16, k1.length, "SM4 默认密钥长度 128 bit");
        assertEquals(16, SM4Util.generateKey(SM4Util.DEFAULT_KEY_SIZE).length);
        assertFalse(Arrays.equals(k1, k2), "两次生成的密钥不应相同");
    }

    @Test
    void cmacIsDeterministic() throws Exception {
        byte[] data = "cmac-data".getBytes();
        byte[] mac1 = SM4Util.doCMac(STD_KEY, data);
        byte[] mac2 = SM4Util.doCMac(STD_KEY, data);
        assertEquals(16, mac1.length, "SM4-CMAC 输出 16 字节");
        assertArrayEquals(mac1, mac2, "CMAC 确定性");
        assertFalse(Arrays.equals(mac1, SM4Util.doCMac(hex("00112233445566778899aabbccddeeff"), data)),
            "不同密钥应产生不同 CMAC");
    }

    @Test
    void gmacProducesRequestedTagLength() {
        byte[] iv = "0123456789abcdef".getBytes();
        byte[] data = "gmac-data".getBytes();
        byte[] tag4 = SM4Util.doGMac(STD_KEY, iv, 4, data);
        byte[] tag16 = SM4Util.doGMac(STD_KEY, iv, 16, data);
        assertEquals(4, tag4.length, "GMAC 标签长度 = tagLength");
        assertEquals(16, tag16.length);
        assertArrayEquals(tag16, SM4Util.doGMac(STD_KEY, iv, 16, data), "GMAC 确定性");
    }

    @Test
    void cbcMacVariants() throws Exception {
        byte[] iv = "0123456789abcdef".getBytes();
        byte[] data = "0123456789abcdef".getBytes();

        byte[] mac = SM4Util.doCBCMac(STD_KEY, iv, data);
        assertEquals(16, mac.length, "CBC-MAC 输出 16 字节");
        assertArrayEquals(mac, SM4Util.doCBCMac(STD_KEY, iv, data), "CBC-MAC 确定性");

        // PKCS7Padding 显式版本
        byte[] mac2 = SM4Util.doCBCMac(STD_KEY, iv, new PKCS7Padding(), data);
        assertEquals(16, mac2.length);

        // NoPadding 版本：数据必须是块大小整数倍
        byte[] mac3 = SM4Util.doCBCMac(STD_KEY, iv, null, data);
        assertEquals(16, mac3.length);
        assertThrows(Exception.class,
            () -> SM4Util.doCBCMac(STD_KEY, iv, null, "not-multiple".getBytes()),
            "NoPadding 且非块长整数倍应抛异常");
    }

    private static byte[] hex(String s) {
        try {
            return Hex.decodeHex(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}