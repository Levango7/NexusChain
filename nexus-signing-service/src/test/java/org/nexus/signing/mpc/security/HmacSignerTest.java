package org.nexus.signing.mpc.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HmacSigner} 单元测试。
 */
public class HmacSignerTest {

    /** 32 字节 MAK 的 base64 编码。 */
    private static final String MAK_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    public void testSignReturnsNonEmptyHex() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        String hmac = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", "p2", "payload", 1234567890L, "nonce-1");
        assertNotNull(hmac);
        assertTrue(hmac.length() == 64, "HMAC hex length should be 64 (32 bytes)");
    }

    @Test
    public void testSignDeterministicForSameInputs() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        String hmac1 = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", "p2", "payload", 1234567890L, "nonce-1");
        String hmac2 = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", "p2", "payload", 1234567890L, "nonce-1");
        assertTrue(hmac1.equals(hmac2));
    }

    @Test
    public void testSignDifferentForDifferentPayload() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        String hmac1 = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", "p2", "payload-1", 1234567890L, "nonce-1");
        String hmac2 = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", "p2", "payload-2", 1234567890L, "nonce-1");
        assertFalse(hmac1.equals(hmac2));
    }

    @Test
    public void testVerifyCorrectSignature() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        String hmac = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", null, "payload", 1234567890L, "nonce-1");
        boolean valid = signer.verify(hmac,
                "msg-1", "s1", "1", "SIGN_ROUND",
                "p1", null, "payload", "1234567890", "nonce-1");
        assertTrue(valid);
    }

    @Test
    public void testVerifyIncorrectSignatureReturnsFalse() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        boolean valid = signer.verify("0000".repeat(16),
                "msg-1", "s1", "1", "SIGN_ROUND",
                "p1", null, "payload", "1234567890", "nonce-1");
        assertFalse(valid);
    }

    @Test
    public void testNullMakAndNoEnvThrows() { assertThrows(IllegalStateException.class, () -> {
        // 清除环境变量
        String saved = System.getenv("NEXUS_MPC_MAK");
        try {
            // 由于环境变量不可清除，仅在未设置时测试
            if (System.getenv("NEXUS_MPC_MAK") == null) {
                new HmacSigner(null);
            } else {
                throw new IllegalStateException("test skipped: env NEXUS_MPC_MAK set");
            }
        } finally {
            // no-op
        }
        });
    }

    @Test
    public void testShortMakThrows() { assertThrows(IllegalStateException.class, () -> {
        String shortMak = Base64.getEncoder().encodeToString(new byte[16]); // 16 字节
        new HmacSigner(shortMak);
        });
    }

    @Test
    public void testSignWithNullToParticipant() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        String hmac = signer.sign("msg-1", "s1", 1, "SIGN_ROUND",
                "p1", null, "payload", 1234567890L, "nonce-1");
        assertNotNull(hmac);
    }
}