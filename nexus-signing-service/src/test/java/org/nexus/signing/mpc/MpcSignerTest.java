package org.nexus.signing.mpc;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link MpcSigner} 单元测试。
 */
public class MpcSignerTest {

    private MpcSigner signer = new MpcSigner();

    private MpcSigningSession newSession(int threshold, int total) {
        return new MpcSigningSession(
                "s1", "w1", "tx-hex",
                new ThresholdPolicy(threshold, total),
                List.of(
                        new MpcParticipant("p1", "h1", "pk1"),
                        new MpcParticipant("p2", "h2", "pk2")));
    }

    private List<MpcKeyShare> twoShares() {
        return List.of(
                new MpcKeyShare("p1", "priv1", "pub1", null),
                new MpcKeyShare("p2", "priv2", "pub2", null));
    }

    @Test
    public void testRunSigningRoundsHappyPath() {
        MpcSigningSession session = newSession(2, 2);
        signer.runSigningRounds(session, twoShares());
        // 应完成所有 SIGN_ROUNDS 轮次，并进入 AGGREGATING
        assertEquals(MpcSigningSession.SessionStatus.AGGREGATING, session.getStatus());
        assertEquals(MpcSigningSession.SIGN_ROUNDS, session.getCurrentRound());
        // 每个参与者应有签名份额
        assertEquals(2, session.getCollectedShareCount());
        // 每轮应有消息记录
        assertEquals(MpcSigningSession.SIGN_ROUNDS, session.getRoundMessages().size());
    }

    @Test(expected = NullPointerException.class)
    public void testNullSessionThrows() {
        signer.runSigningRounds(null, twoShares());
    }

    @Test(expected = NullPointerException.class)
    public void testNullSharesThrows() {
        signer.runSigningRounds(newSession(2, 2), null);
    }

    @Test(expected = MpcProtocolException.class)
    public void testQuorumNotReachedThrows() {
        // threshold=2 但只有 1 个在线参与者
        MpcSigningSession session = new MpcSigningSession(
                "s1", "w1", "tx-hex",
                new ThresholdPolicy(2, 2),
                List.of(
                        new MpcParticipant("p1", "h1", "pk1"),
                        new MpcParticipant("p2", "h2", "pk2", false))); // p2 离线
        signer.runSigningRounds(session, twoShares());
    }

    @Test
    public void testSignatureSharesRecordedWithFrozenPrefix() {
        MpcSigningSession session = newSession(2, 2);
        signer.runSigningRounds(session, twoShares());
        // FROZEN-sig-share- 前缀
        String shareP1 = session.getSignatureShares().get("p1");
        assertNotNull(shareP1);
        assertTrue(shareP1.startsWith("FROZEN-sig-share-"));
    }
}