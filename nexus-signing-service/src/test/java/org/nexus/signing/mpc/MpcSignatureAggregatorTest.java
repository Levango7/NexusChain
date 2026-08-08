package org.nexus.signing.mpc;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MpcSignatureAggregator} 单元测试。
 */
public class MpcSignatureAggregatorTest {

    private final MpcSignatureAggregator aggregator = new MpcSignatureAggregator();

    private MpcSigningSession newSessionWithShares(int threshold, int total, int sharesCollected) {
        MpcSigningSession session = new MpcSigningSession(
                "s1", "w1", "tx-hex",
                new ThresholdPolicy(threshold, total),
                List.of(
                        new MpcParticipant("p1", "h1", "pk1"),
                        new MpcParticipant("p2", "h2", "pk2")));
        for (int i = 1; i <= sharesCollected; i++) {
            session.recordSignatureShare("p" + i, "share" + i);
        }
        return session;
    }

    @Test
    public void testAggregateHappyPath() {
        MpcSigningSession session = newSessionWithShares(2, 2, 2);
        String jointPk = "joint-pk-hex";

        String sig = aggregator.aggregate(session, jointPk);

        assertNotNull(sig);
        assertTrue(sig.startsWith("SIG:"));
        // session 应被标记为 COMPLETED
        assertEquals(MpcSigningSession.SessionStatus.COMPLETED, session.getStatus());
        assertEquals(sig, session.getCombinedSignatureHex());
    }

    @Test(expected = NullPointerException.class)
    public void testNullSessionThrows() {
        aggregator.aggregate(null, "pk");
    }

    @Test(expected = NullPointerException.class)
    public void testNullJointPkThrows() {
        aggregator.aggregate(newSessionWithShares(2, 2, 2), null);
    }

    @Test(expected = MpcProtocolException.class)
    public void testInsufficientSharesThrows() {
        // threshold=2 但只收集了 1 个份额
        MpcSigningSession session = newSessionWithShares(2, 2, 1);
        aggregator.aggregate(session, "pk");
    }

    @Test(expected = MpcProtocolException.class)
    public void testEmptyShareThrows() {
        MpcSigningSession session = newSessionWithShares(2, 2, 2);
        session.recordSignatureShare("p3", ""); // 空份额
        aggregator.aggregate(session, "pk");
    }

    @Test
    public void testAggregatedSignatureContainsAllShares() {
        MpcSigningSession session = newSessionWithShares(2, 2, 2);
        String sig = aggregator.aggregate(session, "joint-pk");
        assertTrue(sig.contains("share1"));
        assertTrue(sig.contains("share2"));
    }
}