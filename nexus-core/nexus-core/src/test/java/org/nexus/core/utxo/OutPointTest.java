package org.nexus.core.utxo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OutPoint} 单元测试。
 */
class OutPointTest {

    @Test
    void settersAndGetters() {
        OutPoint p = new OutPoint();
        byte[] script = new byte[]{1, 2};
        byte[] dataScript = new byte[]{3, 4};
        byte[] txHash = new byte[]{5, 6};

        p.setAmount(1000L);
        p.setScriptLength(2);
        p.setScript(script);
        p.setIndex(3);
        p.setDataScriptLength(2);
        p.setDataScript(dataScript);
        p.setTransactionHash(txHash);
        p.setAddress("addr1");

        assertEquals(1000L, p.getAmount());
        assertEquals(2, p.getScriptLength());
        assertArrayEquals(script, p.getScript());
        assertEquals(3, p.getIndex());
        assertEquals(2, p.getDataScriptLength());
        assertArrayEquals(dataScript, p.getDataScript());
        assertArrayEquals(txHash, p.getTransactionHash());
        assertEquals("addr1", p.getAddress());
    }

    @Test
    void getTransferTargetReturnsNull() {
        OutPoint p = new OutPoint();
        assertNull(p.getTransferTarget());
    }
}