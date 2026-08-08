package org.nexus.core.utxo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InPoint} 单元测试。
 */
class InPointTest {

    @Test
    void settersAndGetters() {
        InPoint p = new InPoint();
        byte[] prevTx = new byte[]{1, 2, 3};
        byte[] script = new byte[]{4, 5};
        byte[] txHash = new byte[]{6, 7};

        p.setPreviousTransactionHash(prevTx);
        p.setOutPointIndex(2);
        p.setScriptLength(script.length);
        p.setScript(script);
        p.setTransactionHash(txHash);
        p.setIntPointIndex(5);

        assertArrayEquals(prevTx, p.getPreviousTransactionHash());
        assertEquals(2, p.getOutPointIndex());
        assertEquals(2, p.getScriptLength());
        assertArrayEquals(script, p.getScript());
        assertArrayEquals(txHash, p.getTransactionHash());
        assertEquals(5, p.getIntPointIndex());
    }

    @Test
    void getTransferOwnerReturnsNull() {
        InPoint p = new InPoint();
        assertNull(p.getTransferOwner());
    }
}