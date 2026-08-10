package org.nexus.oracle.random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RandomProof} Lombok 实体单元测试。
 */
class RandomProofEntityTest {

    @Test
    void builder_shouldSetAllFields() {
        RandomProof p = RandomProof.builder()
                .seed("seed-1")
                .random("rand-val")
                .proof("proof-val")
                .signature("sig-val")
                .generator("gen-val")
                .build();

        assertEquals("seed-1", p.getSeed());
        assertEquals("rand-val", p.getRandom());
        assertEquals("proof-val", p.getProof());
        assertEquals("sig-val", p.getSignature());
        assertEquals("gen-val", p.getGenerator());
    }

    @Test
    void noArgsConstructor_shouldHaveNulls() {
        RandomProof p = new RandomProof();
        assertNull(p.getSeed());
        assertNull(p.getRandom());
        assertNull(p.getProof());
        assertNull(p.getSignature());
        assertNull(p.getGenerator());
    }

    @Test
    void setters_shouldRoundTrip() {
        RandomProof p = new RandomProof();
        p.setSeed("s");
        p.setRandom("r");
        p.setProof("p");
        p.setSignature("sig");
        p.setGenerator("g");

        assertEquals("s", p.getSeed());
        assertEquals("r", p.getRandom());
        assertEquals("p", p.getProof());
        assertEquals("sig", p.getSignature());
        assertEquals("g", p.getGenerator());
    }

    @Test
    void equalsAndHashCode_shouldWork() {
        RandomProof a = RandomProof.builder().random("r1").proof("p1").build();
        RandomProof b = RandomProof.builder().random("r1").proof("p1").build();
        RandomProof c = RandomProof.builder().random("r2").proof("p1").build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
        assertTrue(a.equals(a));
    }

    @Test
    void canEqual_shouldDistinguishTypes() {
        RandomProof p = new RandomProof();
        assertFalse(p.canEqual("string"));
        assertTrue(p.canEqual(new RandomProof()));
    }

    @Test
    void toString_shouldContainFields() {
        RandomProof p = RandomProof.builder().random("r1").proof("p1").build();
        String s = p.toString();
        assertNotNull(s);
        assertTrue(s.contains("r1"));
        assertTrue(s.contains("p1"));
    }
}