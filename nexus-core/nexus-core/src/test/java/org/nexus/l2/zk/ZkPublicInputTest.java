package org.nexus.l2.zk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ZkPublicInput} 单元测试。
 */
class ZkPublicInputTest {

    @Test
    void gettersReturnConstructorValues() {
        List<String> extras = Arrays.asList("k1=v1", "k2=v2");
        ZkPublicInput in = new ZkPublicInput(
                "pre", "post", "batch", 42L, extras);
        assertEquals("pre", in.getPreStateRoot());
        assertEquals("post", in.getPostStateRoot());
        assertEquals("batch", in.getBatchDataHash());
        assertEquals(42L, in.getL1BlockNumber());
        assertEquals(2, in.getExtraInputs().size());
    }

    @Test
    void nullExtraInputsBecomesEmptyList() {
        ZkPublicInput in = new ZkPublicInput("a", "b", "c", 0, null);
        assertNotNull(in.getExtraInputs());
        assertTrue(in.getExtraInputs().isEmpty());
    }

    @Test
    void extraInputsIsUnmodifiable() {
        List<String> extras = Arrays.asList("k=v");
        ZkPublicInput in = new ZkPublicInput("a", "b", "c", 0, extras);
        assertThrows(UnsupportedOperationException.class, () ->
                in.getExtraInputs().add("new"));
    }

    @Test
    void toStringContainsKeyFields() {
        ZkPublicInput in = new ZkPublicInput("pre", "post", "batch", 7, null);
        String s = in.toString();
        assertTrue(s.contains("ZkPublicInput"));
        assertTrue(s.contains("pre"));
        assertTrue(s.contains("post"));
        assertTrue(s.contains("7"));
    }

    @Test
    void nullStateRootsAllowed() {
        ZkPublicInput in = new ZkPublicInput(null, null, null, 0, null);
        assertNull(in.getPreStateRoot());
        assertNull(in.getPostStateRoot());
        assertNull(in.getBatchDataHash());
    }
}