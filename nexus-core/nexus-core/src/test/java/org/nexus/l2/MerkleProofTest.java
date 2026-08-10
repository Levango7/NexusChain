package org.nexus.l2;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MerkleProof} 实体测试。
 */
public class MerkleProofTest {

    @Test
    public void testConstructorAndGetters() {
        List<String> siblings = Arrays.asList("hash1", "hash2", "hash3");
        List<Integer> directions = Arrays.asList(0, 1, 0);

        MerkleProof proof = new MerkleProof("key1", "value1", 5, siblings, directions);

        assertEquals("key1", proof.getKey());
        assertEquals("value1", proof.getValue());
        assertEquals(5, proof.getIndex());
        assertEquals(siblings, proof.getSiblings());
        assertEquals(directions, proof.getDirections());
        assertEquals(3, proof.size());
    }

    @Test
    public void testSizeWithNullSiblings() {
        MerkleProof proof = new MerkleProof("k", "v", 0, null, null);
        assertEquals(0, proof.size());
    }

    @Test
    public void testSizeWithEmptySiblings() {
        MerkleProof proof = new MerkleProof("k", "v", 0, Collections.emptyList(), Collections.emptyList());
        assertEquals(0, proof.size());
    }

    @Test
    public void testGetSiblingsUnmodifiable() {
        List<String> siblings = Arrays.asList("hash1", "hash2");
        MerkleProof proof = new MerkleProof("k", "v", 0, siblings, Collections.emptyList());
        assertThrows(UnsupportedOperationException.class, () -> proof.getSiblings().add("hash3"));
    }

    @Test
    public void testGetDirectionsUnmodifiable() {
        List<Integer> directions = Arrays.asList(0, 1);
        MerkleProof proof = new MerkleProof("k", "v", 0, Collections.emptyList(), directions);
        assertThrows(UnsupportedOperationException.class, () -> proof.getDirections().add(1));
    }
}
