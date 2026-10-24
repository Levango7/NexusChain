package org.nexus.sync.validator;

import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.net.ValidatorSetCodec;
import org.nexus.consensus.finality.net.ValidatorSetCodec.ValidatorSetMessage;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证人集消息 签名/重放 决策测试 —— PLAN-001 签名扩展【切片 2：接线】。
 *
 * <p>覆盖：v1 旧格式逐字节不变与 v1 解码默认值、签名 add 受理、篡改载荷/错误公钥/
 * 畸形签名一律丢弃、重放（nonce 相等/更旧）丢弃、兼容模式 vs enforce 模式、
 * remove 的验签公钥来自注册表。全部纯 JDK + 无 Spring/无 DB。</p>
 */
class ValidatorSetMessageVerifierTest {

    private static final HexFormat HEX = HexFormat.of();

    private final ValidatorSetMessageVerifier verifier = new ValidatorSetMessageVerifier();
    private final ValidatorSetSignatureVerifier crypto = new ValidatorSetSignatureVerifier();

    private static String rawSeedHex(KeyPair kp) {
        byte[] der = kp.getPrivate().getEncoded();
        return HEX.formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    private static String rawPubHex(KeyPair kp) {
        byte[] der = kp.getPublic().getEncoded();
        return HEX.formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    private static KeyPair ed25519() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private String sign(String action, String address, String pub, String stake, long nonce, KeyPair kp) {
        return crypto.sign(
                ValidatorSetCodec.canonicalSigningBytes(action, address, pub, stake, nonce),
                rawSeedHex(kp));
    }

    @Test
    void legacyV1FormatIsByteIdenticalAndDecodesAsUnsigned() {
        byte[] payload = ValidatorSetCodec.encodeAdd("1AddrABC123", "aa11bb22cc33", "1000");
        String expected = "{\"type\":\"validator-set\",\"action\":\"add\",\"address\":\"1AddrABC123\","
                + "\"publicKey\":\"aa11bb22cc33\",\"stakeAmount\":\"1000\"}";
        byte[] expectedBytes = new byte[1 + expected.length()];
        expectedBytes[0] = ValidatorSetCodec.MAGIC;
        System.arraycopy(expected.getBytes(StandardCharsets.UTF_8), 0, expectedBytes, 1, expected.length());
        assertEquals(HEX.formatHex(expectedBytes), HEX.formatHex(payload),
                "v1 编码必须与旧实现逐字节一致（老节点兼容）");

        ValidatorSetMessage msg = ValidatorSetCodec.decode(payload);
        assertEquals(0L, msg.nonce());
        assertNull(msg.signature());
        assertFalse(msg.hasSignature());

        // 兼容模式受理；enforce 模式丢弃
        assertEquals(ValidatorSetMessageVerifier.Decision.ACCEPT_UNSIGNED,
                verifier.decide(msg, null, 0L).decision());
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_UNSIGNED,
                verifier.decide(msg, null, 0L, true).decision());
    }

    @Test
    void signedAddIsAccepted() throws Exception {
        KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 1_750_000_000_000L;
        String sig = sign(ValidatorSetCodec.ACTION_ADD, "addr-A", pub, "1000", nonce, kp);

        ValidatorSetMessage msg = ValidatorSetCodec.decode(
                ValidatorSetCodec.encodeAdd("addr-A", pub, "1000", nonce, sig));
        assertTrue(msg.hasSignature());
        assertEquals(nonce, msg.nonce());

        ValidatorSetMessageVerifier.Result r = verifier.decide(msg, null, 0L);
        assertEquals(ValidatorSetMessageVerifier.Decision.ACCEPT_SIGNED, r.decision());
        assertTrue(r.accepted());
    }

    @Test
    void tamperedStakeIsDroppedEvenWithValidLookingSignature() throws Exception {
        KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 1_750_000_000_001L;
        String sig = sign(ValidatorSetCodec.ACTION_ADD, "addr-A", pub, "1000", nonce, kp);

        // 攻击者保留签名，篡改质押额
        ValidatorSetMessage tampered = ValidatorSetCodec.decode(
                ValidatorSetCodec.encodeAdd("addr-A", pub, "999999", nonce, sig));
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_BAD_SIGNATURE,
                verifier.decide(tampered, null, 0L).decision());
    }

    @Test
    void wrongPublicKeyIsDropped() throws Exception {
        KeyPair kp1 = ed25519();
        KeyPair kp2 = ed25519();
        long nonce = 1_750_000_000_002L;
        String sig = sign(ValidatorSetCodec.ACTION_ADD, "addr-A", rawPubHex(kp1), "1000", nonce, kp1);

        // 消息声明 kp2 的公钥，但签名来自 kp1 ⇒ 拒绝（防冒用他人地址）
        ValidatorSetMessage msg = ValidatorSetCodec.decode(
                ValidatorSetCodec.encodeAdd("addr-A", rawPubHex(kp2), "1000", nonce, sig));
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_BAD_SIGNATURE,
                verifier.decide(msg, null, 0L).decision());
    }

    @Test
    void malformedSignatureIsDroppedNotThrown() throws Exception {
        KeyPair kp = ed25519();
        ValidatorSetMessage msg = ValidatorSetCodec.decode(
                ValidatorSetCodec.encodeAdd("addr-A", rawPubHex(kp), "1000", 7L, "not-base64!!!"));
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_BAD_SIGNATURE,
                verifier.decide(msg, null, 0L).decision());
    }

    @Test
    void replayIsDroppedForEqualOrOlderNonceAndAcceptedForNewer() throws Exception {
        KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 5_000L;
        String sig = sign(ValidatorSetCodec.ACTION_ADD, "addr-A", pub, "1000", nonce, kp);
        ValidatorSetMessage msg = ValidatorSetCodec.decode(
                ValidatorSetCodec.encodeAdd("addr-A", pub, "1000", nonce, sig));

        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_REPLAY,
                verifier.decide(msg, null, 5_000L).decision(), "相等 nonce 是重放");
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_REPLAY,
                verifier.decide(msg, null, 5_001L).decision(), "更旧 nonce 是重放");
        assertEquals(ValidatorSetMessageVerifier.Decision.ACCEPT_SIGNED,
                verifier.decide(msg, null, 4_999L).decision(), "更新 nonce 受理");
    }

    @Test
    void removeVerificationUsesRegisteredPublicKey() throws Exception {
        KeyPair kp = ed25519();
        String pub = rawPubHex(kp);
        long nonce = 6_000L;
        String sig = sign(ValidatorSetCodec.ACTION_REMOVE, "addr-R", null, null, nonce, kp);
        ValidatorSetMessage msg = ValidatorSetCodec.decode(
                ValidatorSetCodec.encodeRemove("addr-R", nonce, sig));

        assertEquals(ValidatorSetMessageVerifier.Decision.ACCEPT_SIGNED,
                verifier.decide(msg, pub, 0L).decision(), "注册表公钥验签通过");
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_BAD_SIGNATURE,
                verifier.decide(msg, null, 0L).decision(), "未登记地址的 remove 无法验签 → 丢弃");
        assertEquals(ValidatorSetMessageVerifier.Decision.DROP_BAD_SIGNATURE,
                verifier.decide(msg, rawPubHex(ed25519()), 0L).decision(), "错误注册表公钥 → 丢弃");
    }
}
