package org.nexus.l2;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RollupBatch} 实体测试。
 */
public class RollupBatchTest {

    @Test
    public void testDefaultConstructor() {
        RollupBatch batch = new RollupBatch();
        assertNull(batch.getBatchId());
        assertNull(batch.getTransactions());
        assertNull(batch.getStateRoot());
        assertNull(batch.getSubmitter());
        assertNull(batch.getStatus());
    }

    @Test
    public void testSettersAndGetters() {
        RollupBatch batch = new RollupBatch();
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0xabc");

        batch.setBatchId(1L);
        batch.setTransactions(Arrays.asList(tx));
        batch.setStateRoot("0xstateRoot");
        batch.setSubmitter("0xsubmitter");
        batch.setStatus(RollupBatchStatus.SUBMITTED);

        assertEquals(1L, batch.getBatchId());
        assertEquals(1, batch.getTransactions().size());
        assertEquals("0xabc", batch.getTransactions().get(0).getTxHash());
        assertEquals("0xstateRoot", batch.getStateRoot());
        assertEquals("0xsubmitter", batch.getSubmitter());
        assertEquals(RollupBatchStatus.SUBMITTED, batch.getStatus());
    }

    @Test
    public void testRollupBatchStatusEnum() {
        RollupBatchStatus[] statuses = RollupBatchStatus.values();
        assertEquals(3, statuses.length);
        assertSame(RollupBatchStatus.SUBMITTED, RollupBatchStatus.valueOf("SUBMITTED"));
        assertSame(RollupBatchStatus.VERIFIED, RollupBatchStatus.valueOf("VERIFIED"));
        assertSame(RollupBatchStatus.CHALLENGED, RollupBatchStatus.valueOf("CHALLENGED"));
    }
}