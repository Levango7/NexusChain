package org.nexus.crypto.ed25519;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ed25519 测试——密钥生成、编码长度、签名验签往返、公钥一致性。
 */
class Ed25519Test {

    @Test
    void generatedKeysHaveExpectedFormatAndLengths() throws Exception {
        Ed25519KeyPair kp = Ed25519.generateKeyPair();
        Ed25519PrivateKey priv = kp.getPrivateKey();
        Ed25519PublicKey pub = kp.getPublicKey();

        assertEquals("ed25519", priv.getAlgorithm());
        assertEquals("ed25519", pub.getAlgorithm());
        assertEquals("ed25519", Ed25519.getAlgorithm());
        assertEquals("", priv.getFormat());
        assertEquals("", pub.getFormat());
        assertEquals(32, priv.getEncoded().length, "私钥编码 32 字节");
        assertEquals(32, pub.getEncoded().length, "公钥编码 32 字节");
    }

    @Test
    void signVerifyRoundTrip() throws Exception {
        Ed25519KeyPair kp = Ed25519.generateKeyPair();
        byte[] msg = "ed25519-message".getBytes();

        byte[] sig = kp.getPrivateKey().sign(msg);
        assertEquals(64, sig.length, "Ed25519 签名 64 字节");
        assertTrue(kp.getPublicKey().verify(msg, sig), "合法签名应验证通过");
        assertFalse(kp.getPublicKey().verify("tampered".getBytes(), sig), "篡改消息应失败");

        byte[] badSig = sig.clone();
        badSig[0] ^= 0x01;
        assertFalse(kp.getPublicKey().verify(msg, badSig), "篡改签名应失败");
    }

    @Test
    void publicKeyDerivableFromPrivateKey() throws Exception {
        Ed25519KeyPair kp = Ed25519.generateKeyPair();
        Ed25519PublicKey derived = kp.getPrivateKey().generatePublicKey();
        assertArrayEquals(kp.getPublicKey().getEncoded(), derived.getEncoded(),
            "私钥导出的公钥应与密钥对公钥一致");
    }

    @Test
    void keysReconstructableFromEncodedBytes() throws Exception {
        Ed25519KeyPair kp = Ed25519.generateKeyPair();
        byte[] privBytes = kp.getPrivateKey().getEncoded();
        byte[] pubBytes = kp.getPublicKey().getEncoded();

        Ed25519PrivateKey rebuiltPriv = new Ed25519PrivateKey(privBytes);
        Ed25519PublicKey rebuiltPub = new Ed25519PublicKey(pubBytes);
        assertArrayEquals(privBytes, rebuiltPriv.getEncoded());
        assertArrayEquals(pubBytes, rebuiltPub.getEncoded());

        byte[] msg = "rebuilt-keys".getBytes();
        assertTrue(rebuiltPub.verify(msg, rebuiltPriv.sign(msg)), "重建密钥应可正常签名验签");

        // decodeFrom 覆盖：先持有另一把合法公钥，再替换编码
        Ed25519KeyPair kp2 = Ed25519.generateKeyPair();
        Ed25519PublicKey patched = new Ed25519PublicKey(kp2.getPublicKey().getEncoded());
        patched.decodeFrom(pubBytes);
        assertArrayEquals(pubBytes, patched.getEncoded(), "decodeFrom 应替换编码");
    }

    @Test
    void distinctKeyPairsProduceDistinctKeysAndSignatures() throws Exception {
        Ed25519KeyPair kp1 = Ed25519.generateKeyPair();
        Ed25519KeyPair kp2 = Ed25519.generateKeyPair();
        assertFalse(java.util.Arrays.equals(kp1.getPrivateKey().getEncoded(), kp2.getPrivateKey().getEncoded()));
        assertFalse(java.util.Arrays.equals(kp1.getPublicKey().getEncoded(), kp2.getPublicKey().getEncoded()));

        byte[] msg = "cross-pair".getBytes();
        assertFalse(kp2.getPublicKey().verify(msg, kp1.getPrivateKey().sign(msg)),
            "跨密钥对验签应失败");
    }

    @Test
    void maxPrivateKeyConstantTakes32BytesUnsigned() {
        assertEquals(32, Ed25519.MAX_PRIVATE_KEY.toByteArray().length,
            "MAX_PRIVATE_KEY 为 252-bit 常数（64 位十六进制，32 字节无符号）");
    }
}