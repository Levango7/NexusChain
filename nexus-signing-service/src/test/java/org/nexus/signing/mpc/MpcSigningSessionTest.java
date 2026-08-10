package org.nexus.signing.mpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MpcSigningSession} 单元测试。
 */
public class MpcSigningSessionTest {

    private MpcSigningSession newSession() {
        return new MpcSigningSession(
                "s1", "w1", "tx-hex",
                new ThresholdPolicy(2, 3),
                List.of(
                        new MpcParticipant("p1", "h1", "pk1"),
                        new MpcParticipant("p2", "h2", "pk2"),
                        new MpcParticipant("p3", "h3", "pk3")));
    }

    @Test
    public void testConstructorAndGetters() {
        MpcSigningSession s = newSession();
        assertEquals(s.getSessionId(), "s1");
        assertEquals(s.getWalletId(), "w1");
        assertEquals(s.getTxDataHex(), "tx-hex");
        assertEquals(2, s.getThresholdPolicy().getThreshold());
        assertEquals(3, s.getParticipants().size());
        assertEquals(MpcSigningSession.SessionStatus.CREATED, s.getStatus());
        assertEquals(0, s.getCurrentRound());
        assertNotNull(s.getCreatedAt());
    }

    @Test
    public void testAdvanceToRound() {
        MpcSigningSession s = newSession();
        s.advanceToRound(1);
        assertEquals(1, s.getCurrentRound());
        assertEquals(MpcSigningSession.SessionStatus.ROUND_IN_PROGRESS, s.getStatus());
        s.advanceToRound(2);
        assertEquals(2, s.getCurrentRound());
    }

    @Test
    public void testAdvanceToRoundBackwardsThrows() { assertThrows(MpcProtocolException.class, () -> {
        MpcSigningSession s = newSession();
        s.advanceToRound(3);
        s.advanceToRound(2); // backwards
        });
    }

    @Test
    public void testAdvanceToRoundExceedsMaxThrows() { assertThrows(MpcProtocolException.class, () -> {
        MpcSigningSession s = newSession();
        s.advanceToRound(MpcSigningSession.SIGN_ROUNDS + 1);
        });
    }

    @Test
    public void testRecordMessage() {
        MpcSigningSession s = newSession();
        s.recordMessage(1, "p1", "msg-hex-1");
        s.recordMessage(1, "p2", "msg-hex-2");
        assertEquals(2, s.getRoundMessages().get(1).size());
        assertEquals(s.getRoundMessages().get(1).get("p1"), "msg-hex-1");
    }

    @Test
    public void testRecordSignatureShare() {
        MpcSigningSession s = newSession();
        s.recordSignatureShare("p1", "share1");
        s.recordSignatureShare("p2", "share2");
        assertEquals(2, s.getCollectedShareCount());
        assertEquals(s.getSignatureShares().get("p1"), "share1");
    }

    @Test
    public void testMarkAggregating() {
        MpcSigningSession s = newSession();
        s.markAggregating();
        assertEquals(MpcSigningSession.SessionStatus.AGGREGATING, s.getStatus());
    }

    @Test
    public void testMarkCompleted() {
        MpcSigningSession s = newSession();
        s.markCompleted("final-sig");
        assertEquals(MpcSigningSession.SessionStatus.COMPLETED, s.getStatus());
        assertEquals(s.getCombinedSignatureHex(), "final-sig");
        assertNotNull(s.getCompletedAt());
    }

    @Test
    public void testMarkFailed() {
        MpcSigningSession s = newSession();
        s.markFailed(MpcProtocolException.Reason.INVALID_SHARE, "bad share", "p2");
        assertEquals(MpcSigningSession.SessionStatus.FAILED, s.getStatus());
        assertEquals(s.getBlamedParticipant(), "p2");
        assertNotNull(s.getCompletedAt());
    }

    @Test
    public void testMarkExpired() {
        MpcSigningSession s = newSession();
        s.markExpired();
        assertEquals(MpcSigningSession.SessionStatus.EXPIRED, s.getStatus());
        assertNotNull(s.getCompletedAt());
    }

    @Test
    public void testHasSufficientSharesTrue() {
        MpcSigningSession s = newSession(); // threshold=2
        s.recordSignatureShare("p1", "s1");
        assertFalse(s.hasSufficientShares());
        s.recordSignatureShare("p2", "s2");
        assertTrue(s.hasSufficientShares());
    }

    @Test
    public void testToString() {
        MpcSigningSession s = newSession();
        s.recordSignatureShare("p1", "s1");
        String str = s.toString();
        assertTrue(str.contains("s1"));
        assertTrue(str.contains("w1"));
        assertTrue(str.contains("1/2"));
    }
}