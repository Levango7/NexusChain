package org.nexus.signing.mpc.security;

import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.MpcMessage;
import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MpcMessageSecurityService} 单元测试。
 */
public class MpcMessageSecurityServiceTest {

    private static final String MAK_BASE64 = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    public void testSecureOutboundDisabledReturnsOriginal() {
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                null, null, null, false);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage secured = svc.secureOutbound(msg);
        assertEquals(msg, secured);
        assertTrue(!svc.isSecurityEnabled());
    }

    @Test
    public void testSecureOutboundWithHmac() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                signer, null, null, true);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage secured = svc.secureOutbound(msg);
        assertNotNull(secured.getHmacHex());
        assertTrue(secured.getHmacHex().length() == 64);
    }

    @Test
    public void testSecureOutboundNullSignerReturnsOriginal() {
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                null, null, null, true);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage secured = svc.secureOutbound(msg);
        assertEquals(msg, secured);
    }

    @Test
    public void testVerifyInboundDisabledReturnsOriginal() {
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                null, null, null, false);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage verified = svc.verifyInbound(msg);
        assertEquals(msg, verified);
    }

    @Test
    public void testVerifyInboundWithValidHmac() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                signer, null, null, true);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage secured = svc.secureOutbound(msg);
        MpcMessage verified = svc.verifyInbound(secured);
        assertEquals(msg, verified);
    }

    @Test(expected = MpcProtocolException.class)
    public void testVerifyInboundMissingHmacThrows() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                signer, null, null, true);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        svc.verifyInbound(msg); // 无 HMAC
    }

    @Test(expected = MpcProtocolException.class)
    public void testVerifyInboundInvalidHmacThrows() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                signer, null, null, true);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload").withHmac("0000".repeat(16));
        svc.verifyInbound(msg);
    }

    @Test
    public void testVerifyInboundWithNonceTracker() {
        HmacSigner signer = new HmacSigner(MAK_BASE64);
        NonceTracker tracker = new NonceTracker();
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                signer, tracker, null, true);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage secured = svc.secureOutbound(msg);
        MpcMessage verified = svc.verifyInbound(secured);
        assertEquals(msg, verified);
    }

    @Test
    public void testGetTlsContext() {
        MpcMessageSecurityService svc = new MpcMessageSecurityService(
                null, null, null, true);
        // tlsContext 为 null
        assertEquals(null, svc.getTlsContext());
    }

    @Test
    public void testIsSecurityEnabled() {
        MpcMessageSecurityService enabled = new MpcMessageSecurityService(
                null, null, null, true);
        MpcMessageSecurityService disabled = new MpcMessageSecurityService(
                null, null, null, false);
        assertTrue(enabled.isSecurityEnabled());
        assertTrue(!disabled.isSecurityEnabled());
    }
}