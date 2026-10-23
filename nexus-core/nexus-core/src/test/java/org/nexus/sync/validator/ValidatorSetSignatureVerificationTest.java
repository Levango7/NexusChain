package org.nexus.sync.validator;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证人集消息签名【第一切片】测试 —— 与 {@code docs/plan/PLAN-001-signature-extension.md} §三 对齐。
 *
 * <p>覆盖：RFC 8032 官方向量（raw 密钥路径与标准逐字节一致）、往返、篡改拒绝、错误公钥拒绝、
 * 畸形签名拒绝、无签名向后兼容、重放纯函数边界。全部为纯 JDK 单元测试（无网络、无 DB、无新依赖）。
 */
public class ValidatorSetSignatureVerificationTest {

    private static final HexFormat HEX = HexFormat.of();
    private final ValidatorSetSignatureVerifier verifier = new ValidatorSetSignatureVerifier();

    /** 从 JDK KeyPair 提取 raw 32 字节种子 hex（PKCS#8 DER 末 32 字节为种子）。 */
    private static String rawSeedHex(KeyPair kp) {
        byte[] der = kp.getPrivate().getEncoded();
        return HEX.formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    /** 从 JDK KeyPair 提取 raw 32 字节公钥 hex（SPKI DER 末 32 字节为公钥）。 */
    private static String rawPubHex(KeyPair kp) {
        byte[] der = kp.getPublic().getEncoded();
        return HEX.formatHex(Arrays.copyOfRange(der, der.length - 32, der.length));
    }

    @Test
    void rfc8032TestVector1EmptyMessage() {
        // RFC 8032 §7.1 TEST 1（空消息）：证明 raw 种子/公钥的 DER 包装路径与标准逐字节一致。
        String seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
        String pub = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
        String expectedSigHex = "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
            + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b";

        String sig = verifier.sign(new byte[0], seed);
        assertEquals(expectedSigHex, HEX.formatHex(Base64.getDecoder().decode(sig)),
            "signature must match RFC 8032 TEST 1 byte-for-byte");
        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.VALID,
            verifier.check(new byte[0], pub, sig));
    }

    @Test
    void roundTripSignVerifyIsValid() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = "validator-set:add:bbbbbbbbbbbbbbbb".getBytes(StandardCharsets.UTF_8);

        String sig = verifier.sign(payload, rawSeedHex(kp));

        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.VALID,
            verifier.check(payload, rawPubHex(kp), sig));
    }

    @Test
    void tamperedPayloadIsInvalid() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = "validator-set:add:cccccccccccccccc".getBytes(StandardCharsets.UTF_8);
        String sig = verifier.sign(payload, rawSeedHex(kp));

        byte[] tampered = payload.clone();
        tampered[0] ^= 0x01;

        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.INVALID,
            verifier.check(tampered, rawPubHex(kp), sig));
    }

    @Test
    void wrongPublicKeyIsInvalid() throws Exception {
        KeyPair kp1 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair kp2 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = "validator-set:remove:dddddddddddddddd".getBytes(StandardCharsets.UTF_8);
        String sig = verifier.sign(payload, rawSeedHex(kp1));

        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.INVALID,
            verifier.check(payload, rawPubHex(kp2), sig));
    }

    @Test
    void malformedSignatureIsInvalid() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = "validator-set:add:eeeeeeeeeeeeeeee".getBytes(StandardCharsets.UTF_8);

        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.INVALID,
            verifier.check(payload, rawPubHex(kp), "not-base64!!!"));
        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.INVALID,
            verifier.check(payload, rawPubHex(kp), Base64.getEncoder().encodeToString(new byte[10])));
    }

    @Test
    void noSignatureIsBackwardCompatible() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] payload = "legacy-unsigned-broadcast".getBytes(StandardCharsets.UTF_8);

        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.NO_SIGNATURE,
            verifier.check(payload, rawPubHex(kp), null));
        assertEquals(ValidatorSetSignatureVerifier.SignatureCheckResult.NO_SIGNATURE,
            verifier.check(payload, rawPubHex(kp), ""));
    }

    @Test
    void replayDetectionPureFunction() {
        assertFalse(verifier.isReplay(1L, 0L), "first nonce (1 > 0) is not replay");
        assertTrue(verifier.isReplay(5L, 5L), "same nonce is replay");
        assertTrue(verifier.isReplay(5L, 6L), "older nonce is replay");
        assertFalse(verifier.isReplay(7L, 6L), "newer nonce is not replay");
    }
}
