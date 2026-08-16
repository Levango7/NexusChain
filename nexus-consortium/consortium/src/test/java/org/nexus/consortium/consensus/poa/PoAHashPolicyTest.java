package org.nexus.consortium.consensus.poa;

import org.junit.jupiter.api.Test;
import org.nexus.common.HexBytes;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PoAHashPolicy 单元测试。
 * 覆盖 HashPolicy 接口实现。
 */
public class PoAHashPolicyTest {

    @Test
    public void testImplementsHashPolicy() {
        PoAHashPolicy policy = new PoAHashPolicy();
        assertNotNull(policy);
        assertTrue(policy instanceof org.nexus.common.HashPolicy);
    }

    @Test
    public void testGetHashFromTransaction() {
        org.nexus.common.Transaction tx = org.nexus.common.Transaction.builder()
                .blockHash(new HexBytes(new byte[]{1, 2}))
                .height(1L)
                .version(1)
                .type(0)
                .createdAt(100L)
                .nonce(1L)
                .from(new HexBytes(new byte[]{3, 4}))
                .gasPrice(0L)
                .amount(100L)
                .payload(new HexBytes(new byte[]{5, 6}))
                .to(new HexBytes(new byte[]{7, 8}))
                .signature(new HexBytes(new byte[]{9, 10}))
                .hash(new HexBytes(new byte[]{11, 12}))
                .build();
        PoAHashPolicy policy = new PoAHashPolicy();
        HexBytes hash = policy.getHash(tx);
        assertNotNull(hash);
        assertTrue(hash.getBytes().length > 0);
    }

    @Test
    public void testGetHashFromHeader() {
        org.nexus.common.Header header = org.nexus.common.Header.builder()
                .hash(new HexBytes(new byte[]{1, 2}))
                .version(1)
                .hashPrev(new HexBytes(new byte[]{3, 4}))
                .merkleRoot(new HexBytes(new byte[]{5, 6}))
                .height(1L)
                .createdAt(100L)
                .payload(new HexBytes(new byte[]{7, 8}))
                .build();
        PoAHashPolicy policy = new PoAHashPolicy();
        HexBytes hash = policy.getHash(header);
        assertNotNull(hash);
        assertTrue(hash.getBytes().length > 0);
    }
}