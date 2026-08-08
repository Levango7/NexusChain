package org.nexus.l2.challenge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChallengeConflictResult} 枚举单元测试。
 */
class ChallengeConflictResultTest {

    @Test
    void firstValidIsAccepted() {
        assertTrue(ChallengeConflictResult.FIRST_VALID.isAccepted());
        assertFalse(ChallengeConflictResult.FIRST_VALID.isBondRefunded());
        assertFalse(ChallengeConflictResult.FIRST_VALID.isBondSlashed());
    }

    @Test
    void duplicateAfterValidIsAcceptedAndRefunded() {
        assertTrue(ChallengeConflictResult.DUPLICATE_AFTER_VALID.isAccepted());
        assertTrue(ChallengeConflictResult.DUPLICATE_AFTER_VALID.isBondRefunded());
        assertFalse(ChallengeConflictResult.DUPLICATE_AFTER_VALID.isBondSlashed());
    }

    @Test
    void invalidProofIsSlashed() {
        assertFalse(ChallengeConflictResult.INVALID_PROOF.isAccepted());
        assertFalse(ChallengeConflictResult.INVALID_PROOF.isBondRefunded());
        assertTrue(ChallengeConflictResult.INVALID_PROOF.isBondSlashed());
    }

    @Test
    void windowClosedNotAccepted() {
        assertFalse(ChallengeConflictResult.WINDOW_CLOSED.isAccepted());
        assertFalse(ChallengeConflictResult.WINDOW_CLOSED.isBondRefunded());
        assertFalse(ChallengeConflictResult.WINDOW_CLOSED.isBondSlashed());
    }

    @Test
    void noBondNotAccepted() {
        assertFalse(ChallengeConflictResult.NO_BOND.isAccepted());
    }

    @Test
    void batchNotFoundNotAccepted() {
        assertFalse(ChallengeConflictResult.BATCH_NOT_FOUND.isAccepted());
    }
}