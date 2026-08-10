package org.nexus.oracle.random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultRandomOracle} 单元测试。
 */
class DefaultRandomOracleTest {

    private DefaultRandomOracle oracle;

    @BeforeEach
    void setUp() {
        oracle = new DefaultRandomOracle("test-vrf-secret");
    }

    @Test
    void generateRandom_shouldProduceValidProof() {
        RandomProof proof = oracle.generateRandom("seed-123");

        assertNotNull(proof);
        assertNotNull(proof.getRandom());
        assertNotNull(proof.getProof());
        assertNotNull(proof.getSignature());
        assertNotNull(proof.getGenerator());
        assertEquals("seed-123", proof.getSeed());
    }

    @Test
    void generateRandom_shouldBeDeterministic() {
        RandomProof first = oracle.generateRandom("seed-abc");
        RandomProof second = oracle.generateRandom("seed-abc");

        assertEquals(first.getRandom(), second.getRandom());
        assertEquals(first.getProof(), second.getProof());
    }

    @Test
    void generateRandom_differentSeeds_shouldProduceDifferentRandoms() {
        RandomProof a = oracle.generateRandom("seed-a");
        RandomProof b = oracle.generateRandom("seed-b");

        assertFalse(a.getRandom().equals(b.getRandom()));
    }

    @Test
    void generateRandom_blankSeed_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> oracle.generateRandom(""));
        assertThrows(IllegalArgumentException.class, () -> oracle.generateRandom(null));
    }

    @Test
    void verifyRandom_validPair_shouldPass() {
        RandomProof proof = oracle.generateRandom("seed-verify");

        assertTrue(oracle.verifyRandom(proof.getRandom(), proof.getProof()));
    }

    @Test
    void verifyRandom_tamperedRandom_shouldFail() {
        RandomProof proof = oracle.generateRandom("seed-tamper");

        assertFalse(oracle.verifyRandom(proof.getRandom() + "x", proof.getProof()));
    }

    @Test
    void verifyRandom_tamperedProof_shouldFail() {
        RandomProof proof = oracle.generateRandom("seed-tamper2");

        assertFalse(oracle.verifyRandom(proof.getRandom(), proof.getProof() + "y"));
    }

    @Test
    void verifyRandom_nullInputs_shouldFail() {
        assertFalse(oracle.verifyRandom(null, "proof"));
        assertFalse(oracle.verifyRandom("random", null));
        assertFalse(oracle.verifyRandom("", ""));
    }

    @Test
    void verifyRandom_differentSecret_shouldFail() {
        DefaultRandomOracle otherOracle = new DefaultRandomOracle("different-secret");
        RandomProof proof = oracle.generateRandom("seed-cross");

        // proof 由 oracle 的密钥产出，otherOracle 用不同密钥重算应不一致
        assertFalse(otherOracle.verifyRandom(proof.getRandom(), proof.getProof()));
    }
}
