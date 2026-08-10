package org.nexus.signing.mpc;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MpcSignSession} 单元测试。
 */
public class MpcSignSessionTest {

    @Test
    public void testGettersAndSetters() {
        MpcSignSession session = new MpcSignSession();
        session.setSessionId("s1");
        session.setWalletId("w1");
        session.setTxData("tx-hex");
        session.setStatus(MpcSignSession.SessionStatus.COMPLETED);
        LocalDateTime created = LocalDateTime.now();
        session.setCreatedAt(created);
        LocalDateTime completed = LocalDateTime.now();
        session.setCompletedAt(completed);
        session.setCombinedSignature("sig-hex");

        Set<String> signed = new HashSet<>();
        signed.add("p1");
        session.setSignedParticipants(signed);

        Map<String, String> shares = new HashMap<>();
        shares.put("p1", "share1");
        session.setSignatureShares(shares);

        assertEquals(session.getSessionId(), "s1");
        assertEquals(session.getWalletId(), "w1");
        assertEquals(session.getTxData(), "tx-hex");
        assertEquals(MpcSignSession.SessionStatus.COMPLETED, session.getStatus());
        assertEquals(created, session.getCreatedAt());
        assertEquals(completed, session.getCompletedAt());
        assertEquals(session.getCombinedSignature(), "sig-hex");
        assertEquals(1, session.getSignedParticipants().size());
        assertEquals(1, session.getSignatureShares().size());
    }

    @Test
    public void testDefaultStatusPending() {
        MpcSignSession session = new MpcSignSession();
        assertEquals(MpcSignSession.SessionStatus.PENDING, session.getStatus());
    }

    @Test
    public void testDefaultCollectionsNotNull() {
        MpcSignSession session = new MpcSignSession();
        assertNotNull(session.getSignedParticipants());
        assertNotNull(session.getSignatureShares());
        assertEquals(0, session.getSignedParticipants().size());
        assertEquals(0, session.getSignatureShares().size());
    }

    @Test
    public void testAllStatusValues() {
        MpcSignSession session = new MpcSignSession();
        for (MpcSignSession.SessionStatus s : MpcSignSession.SessionStatus.values()) {
            session.setStatus(s);
            assertEquals(s, session.getStatus());
        }
    }

    @Test
    public void testDefaultCombinedSignatureNull() {
        MpcSignSession session = new MpcSignSession();
        assertNull(session.getCombinedSignature());
    }
}