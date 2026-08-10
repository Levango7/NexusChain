package org.nexus.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConsensusResult} 单元测试。
 */
class ConsensusResultTest {

    @Test
    void constructorSetsFields() {
        ConsensusResult r = new ConsensusResult(200, "ok");
        assertEquals(200, r.code);
        assertEquals("ok", r.message);
    }

    @Test
    void successReturnsEncodedJson() {
        byte[] encoded = ConsensusResult.SUCCESS("done");
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        String json = new String(encoded);
        assertTrue(json.contains("200"));
        assertTrue(json.contains("done"));
    }

    @Test
    void errorReturnsEncodedJson() {
        byte[] encoded = ConsensusResult.ERROR("bad");
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        String json = new String(encoded);
        assertTrue(json.contains("400"));
        assertTrue(json.contains("bad"));
    }
}