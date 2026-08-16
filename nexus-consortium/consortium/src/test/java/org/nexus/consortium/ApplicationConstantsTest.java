package org.nexus.consortium;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ApplicationConstants 单元测试。
 * 覆盖常量值校验。
 */
public class ApplicationConstantsTest {

    @Test
    public void testConsensusNameProperty() {
        assertEquals("consortium.consensus.name", ApplicationConstants.CONSENSUS_NAME_PROPERTY);
    }

    @Test
    public void testConsensusPoa() {
        assertEquals("poa", ApplicationConstants.CONSENSUS_POA);
    }

    @Test
    public void testConsensusNone() {
        assertEquals("none", ApplicationConstants.CONSENSUS_NONE);
    }

    @Test
    public void testPublicKeySize() {
        assertEquals(32, ApplicationConstants.PUBLIC_KEY_SIZE);
    }
}