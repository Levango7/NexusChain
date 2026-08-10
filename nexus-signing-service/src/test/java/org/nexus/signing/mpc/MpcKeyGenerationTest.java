package org.nexus.signing.mpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MpcKeyGeneration} 单元测试。
 */
public class MpcKeyGenerationTest {

    private final MpcKeyGeneration keyGen = new MpcKeyGeneration();

    private List<MpcParticipant> threeParticipants() {
        return List.of(
                new MpcParticipant("p1", "h1", "pkshare1"),
                new MpcParticipant("p2", "h2", "pkshare2"),
                new MpcParticipant("p3", "h3", "pkshare3"));
    }

    @Test
    public void testGenerateHappyPath() {
        MpcKeyGeneration.DkgResult result = keyGen.generate(threeParticipants(), 2);

        assertNotNull(result);
        assertNotNull(result.getJointPublicKeyHex());
        assertEquals(3, result.getShares().size());
        assertNotNull(result.getCompletedAt());

        // joint public key 应包含所有参与方的 publicShareHex
        assertTrue(result.getJointPublicKeyHex().startsWith("JOINT-PK:"));
        assertTrue(result.getJointPublicKeyHex().contains("pkshare1"));
        assertTrue(result.getJointPublicKeyHex().contains("pkshare2"));
        assertTrue(result.getJointPublicKeyHex().contains("pkshare3"));

        // 每个份额应包含 FROZEN 私有份额
        for (MpcKeyShare share : result.getShares()) {
            assertTrue(share.getPrivateShareHex().startsWith("FROZEN-private-share-"));
            assertNotNull(share.getPaillierPublicKeyHex());
            assertTrue(share.getPaillierPublicKeyHex().startsWith("FROZEN-paillier-"));
        }
    }

    @Test
    public void testNullParticipantsThrows() { assertThrows(NullPointerException.class, () -> {
        keyGen.generate(null, 2);
        });
    }

    @Test
    public void testTooFewParticipantsThrows() { assertThrows(MpcProtocolException.class, () -> {
        keyGen.generate(List.of(new MpcParticipant("p1", "h1", "pk1")), 1);
        });
    }

    @Test
    public void testRoundStateMarkCompleted() {
        MpcKeyGeneration.RoundState rs = new MpcKeyGeneration.RoundState("p1", 1);
        assertEquals(rs.getParticipantId(), "p1");
        assertEquals(1, rs.getRound());
        assertTrue(!rs.isCompleted());
        rs.markCompleted();
        assertTrue(rs.isCompleted());
        assertNotNull(rs.getReceivedMessages());
    }

    @Test
    public void testDkgRoundsConstant() {
        assertEquals(4, MpcKeyGeneration.DKG_ROUNDS);
    }
}