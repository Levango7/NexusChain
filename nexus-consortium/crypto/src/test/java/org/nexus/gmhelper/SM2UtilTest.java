package org.nexus.gmhelper;

import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SM2 测试——密钥格式（32/64 字节）、加解密、签名验签、DER 编解码往返与密文分解。
 */
class SM2UtilTest {

    private static final byte[] DEFAULT_ID = "1234567812345678".getBytes();

    @Test
    void generatedKeyPairsHaveExpectedRawSizes() throws Exception {
        AsymmetricCipherKeyPair params = SM2Util.generateKeyPairParameter();
        assertNotNull(params.getPrivate());
        assertNotNull(params.getPublic());
        // D 为 256-bit 大数，去掉符号位后应为 31~33 字节（带符号位）
        int dLen = ((ECPrivateKeyParameters) params.getPrivate()).getD().toByteArray().length;
        assertTrue(dLen >= 31 && dLen <= 33, "D 编码应在 31~33 字节之间，实际 " + dLen);
        assertTrue(params.getPublic() instanceof ECPublicKeyParameters);

        KeyPair jce = SM2Util.generateKeyPair();
        byte[] rawPriv = SM2Util.getRawPrivateKey((BCECPrivateKey) jce.getPrivate());
        byte[] rawPub = SM2Util.getRawPublicKey((BCECPublicKey) jce.getPublic());
        assertEquals(32, rawPriv.length, "原始私钥应为 32 字节");
        assertEquals(64, rawPub.length, "原始公钥 X||Y 应为 64 字节");
        assertEquals(0x04, ((BCECPublicKey) jce.getPublic()).getQ().getEncoded(false)[0],
            "非压缩公钥前缀应为 0x04");
    }

    @Test
    void encryptDecryptRoundTripWithParametersApi() throws Exception {
        AsymmetricCipherKeyPair kp = SM2Util.generateKeyPairParameter();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) kp.getPublic();
        ECPrivateKeyParameters pri = (ECPrivateKeyParameters) kp.getPrivate();

        byte[] plain = new byte[100];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) i;
        }
        byte[] ct = SM2Util.encrypt(pub, plain);
        assertFalse(java.util.Arrays.equals(plain, ct), "SM2 加密输出不应等于明文");
        assertArrayEquals(plain, SM2Util.decrypt(pri, ct), "参数 API 解密应还原明文");
    }

    @Test
    void encryptDecryptRoundTripWithJceKeyApi() throws Exception {
        KeyPair kp = SM2Util.generateKeyPair();
        byte[] plain = "nexus-consortium-sm2-payload".getBytes();
        byte[] ct = SM2Util.encrypt((BCECPublicKey) kp.getPublic(), plain);
        assertArrayEquals(plain, SM2Util.decrypt((BCECPrivateKey) kp.getPrivate(), ct),
            "JCE 密钥 API 解密应还原明文");
    }

    @Test
    void signVerifyWithDefaultId() throws Exception {
        AsymmetricCipherKeyPair kp = SM2Util.generateKeyPairParameter();
        ECPrivateKeyParameters pri = (ECPrivateKeyParameters) kp.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) kp.getPublic();
        byte[] msg = "sign-me".getBytes();

        byte[] sig = SM2Util.sign(pri, msg);
        assertTrue(SM2Util.verify(pub, msg, sig), "合法签名应验证通过");
        assertFalse(SM2Util.verify(pub, "other".getBytes(), sig), "篡改消息应验证失败");
        byte[] tampered = sig.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertFalse(SM2Util.verify(pub, msg, tampered), "篡改签名应验证失败");
    }

    @Test
    void signVerifyWithExplicitId() throws Exception {
        AsymmetricCipherKeyPair kp = SM2Util.generateKeyPairParameter();
        ECPrivateKeyParameters pri = (ECPrivateKeyParameters) kp.getPrivate();
        ECPublicKeyParameters pub = (ECPublicKeyParameters) kp.getPublic();
        byte[] msg = "id-bound".getBytes();
        byte[] customId = "my-custom-id".getBytes();

        byte[] sig = SM2Util.sign(pri, customId, msg);
        assertTrue(SM2Util.verify(pub, customId, msg, sig), "相同 ID 应验证通过");
        assertFalse(SM2Util.verify(pub, DEFAULT_ID, msg, sig), "不同 ID 应验证失败");
        assertFalse(SM2Util.verify(pub, null, msg, sig), "ID 缺失（默认 ID）应验证失败");
    }

    @Test
    void signVerifyWithJceKeyApi() throws Exception {
        KeyPair kp = SM2Util.generateKeyPair();
        byte[] msg = "jce-sign".getBytes();

        byte[] sig = SM2Util.sign((BCECPrivateKey) kp.getPrivate(), msg);
        assertTrue(SM2Util.verify((BCECPublicKey) kp.getPublic(), msg, sig),
            "JCE 密钥 API 验签应通过");

        byte[] withId = "jce-id".getBytes();
        byte[] sig2 = SM2Util.sign((BCECPrivateKey) kp.getPrivate(), withId, msg);
        assertTrue(SM2Util.verify((BCECPublicKey) kp.getPublic(), withId, msg, sig2),
            "JCE 密钥 + 显式 ID 验签应通过");
    }

    @Test
    void derSignEncodeDecodeRoundTrip() throws Exception {
        AsymmetricCipherKeyPair kp = SM2Util.generateKeyPairParameter();
        byte[] sig = SM2Util.sign((ECPrivateKeyParameters) kp.getPrivate(), "der".getBytes());

        byte[] raw = SM2Util.decodeDERSM2Sign(sig);
        assertEquals(64, raw.length, "纯 R||S 签名应为 64 字节");
        assertArrayEquals(sig, SM2Util.encodeSM2SignToDER(raw), "DER 编解码应可逆");
    }

    @Test
    void parseCipherAndDerCipherRoundTrip() throws Exception {
        AsymmetricCipherKeyPair kp = SM2Util.generateKeyPairParameter();
        byte[] ct = SM2Util.encrypt((ECPublicKeyParameters) kp.getPublic(),
            "parse-me".getBytes());

        SM2Cipher parsed = SM2Util.parseSM2Cipher(ct);
        assertEquals(65, parsed.getC1().length, "C1 为非压缩点，65 字节");
        assertEquals(32, parsed.getC3().length, "C3 为 SM3 摘要，32 字节");
        assertEquals(ct.length - 65 - 32, parsed.getC2().length, "C2 长度 = 总长 - C1 - C3");
        assertArrayEquals(ct, parsed.getCipherText());

        byte[] der = SM2Util.encodeSM2CipherToDER(ct);
        assertNotNull(ASN1Primitive.fromByteArray(der), "DER 密文应为合法 ASN.1");
        assertArrayEquals(ct, SM2Util.decodeDERSM2Cipher(der), "DER 密文编解码应可逆");

        int curveLen = SM2Util.CURVE_LEN;
        byte[] der2 = SM2Util.encodeSM2CipherToDER(curveLen, SM2Util.SM3_DIGEST_LENGTH, ct);
        assertArrayEquals(ct, SM2Util.decodeDERSM2Cipher(der2), "显式长度 DER 编解码应可逆");
    }

    @Test
    void curveConstantsAreSelfConsistent() {
        assertEquals(32, SM2Util.CURVE_LEN, "SM2 曲线长度应为 32 字节");
        assertEquals(32, SM2Util.SM3_DIGEST_LENGTH);
        assertEquals(SM2Util.SM2_ECC_GX, SM2Util.G_POINT.getAffineXCoord().toBigInteger());
        assertEquals(SM2Util.SM2_ECC_GY, SM2Util.G_POINT.getAffineYCoord().toBigInteger());
        // 基点与阶/余因子自洽：n * G = 无穷远点，h 为余因子
        assertTrue(SM2Util.G_POINT.multiply(SM2Util.SM2_ECC_N).isInfinity(),
            "n*G 应为无穷远点");
        assertEquals(SM2Util.SM2_ECC_H, SM2Util.DOMAIN_PARAMS.getH());
        assertEquals(SM2Util.SM2_ECC_N, SM2Util.DOMAIN_PARAMS.getN());
        assertEquals(SM2Util.SM2_ECC_B, SM2Util.JDK_CURVE.getB());
        assertEquals(SM2Util.SM2_ECC_P, ((java.security.spec.ECFieldFp) SM2Util.JDK_CURVE.getField()).getP());
        assertEquals(SM2Util.SM2_ECC_N, SM2Util.JDK_EC_SPEC.getOrder());
        assertEquals(SM2Util.SM2_ECC_GX, SM2Util.JDK_G_POINT.getAffineX());
        assertEquals(SM2Util.SM2_ECC_GY, SM2Util.JDK_G_POINT.getAffineY());
    }
}