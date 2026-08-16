package org.nexus.consortium.consensus.poa;

import org.junit.jupiter.api.Test;
import org.nexus.common.HexBytes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PoAConstants 单元测试。
 * 覆盖常量初始化与值校验。
 */
public class PoAConstantsTest {

    @Test
    public void testZeroBytesConstant() {
        HexBytes zeroBytes = PoAConstants.ZERO_BYTES;
        assertNotNull(zeroBytes);
        assertEquals(32, zeroBytes.getBytes().length);
        for (byte b : zeroBytes.getBytes()) {
            assertEquals(0, b);
        }
    }

    @Test
    public void testBlockVersionConstant() {
        int version = PoAConstants.BLOCK_VERSION;
        assertTrue(version != 0);
    }

    @Test
    public void testTransactionVersionConstant() {
        int version = PoAConstants.TRANSACTION_VERSION;
        assertTrue(version != 0);
    }

    @Test
    public void testBlockVersionEqualsTransactionVersion() {
        assertEquals(PoAConstants.BLOCK_VERSION, PoAConstants.TRANSACTION_VERSION);
    }
}