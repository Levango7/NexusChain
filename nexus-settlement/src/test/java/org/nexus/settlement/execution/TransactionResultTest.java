package org.nexus.settlement.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TransactionResult} 单元测试。
 */
class TransactionResultTest {

    @Test
    void defaultConstructor_shouldHaveNulls() {
        TransactionResult r = new TransactionResult();
        assertNull(r.getTxHash());
        assertNull(r.getStatus());
        assertEquals(0, r.getConfirmations());
        assertNull(r.getError());
        assertFalse(r.isSimulated());
        assertFalse(r.isSuccess());
    }

    @Test
    void fullConstructor_shouldSetAllFields() {
        TransactionResult r = new TransactionResult(
                "0xabc", TransactionResult.Status.SUCCESS, 3, null, true);

        assertEquals("0xabc", r.getTxHash());
        assertEquals(TransactionResult.Status.SUCCESS, r.getStatus());
        assertEquals(3, r.getConfirmations());
        assertNull(r.getError());
        assertTrue(r.isSimulated());
        assertTrue(r.isSuccess());
    }

    @Test
    void success_shouldHaveSuccessStatusAndNoError() {
        TransactionResult r = TransactionResult.success("0xabc", 5, false);

        assertEquals(TransactionResult.Status.SUCCESS, r.getStatus());
        assertEquals("0xabc", r.getTxHash());
        assertEquals(5, r.getConfirmations());
        assertNull(r.getError());
        assertFalse(r.isSimulated());
        assertTrue(r.isSuccess());
    }

    @Test
    void pending_shouldHavePendingStatus() {
        TransactionResult r = TransactionResult.pending("0xabc", 1, true);

        assertEquals(TransactionResult.Status.PENDING_CONFIRMATION, r.getStatus());
        assertEquals("0xabc", r.getTxHash());
        assertEquals(1, r.getConfirmations());
        assertTrue(r.isSimulated());
        assertFalse(r.isSuccess());
    }

    @Test
    void failure_shouldHaveFailedStatusAndError() {
        TransactionResult r = TransactionResult.failure("boom", true);

        assertEquals(TransactionResult.Status.FAILED, r.getStatus());
        assertNull(r.getTxHash());
        assertEquals(0, r.getConfirmations());
        assertEquals("boom", r.getError());
        assertTrue(r.isSimulated());
        assertFalse(r.isSuccess());
    }

    @Test
    void setters_shouldRoundTrip() {
        TransactionResult r = new TransactionResult();
        r.setTxHash("0xxyz");
        r.setStatus(TransactionResult.Status.SUCCESS);
        r.setConfirmations(10);
        r.setError(null);
        r.setSimulated(true);

        assertEquals("0xxyz", r.getTxHash());
        assertEquals(TransactionResult.Status.SUCCESS, r.getStatus());
        assertEquals(10, r.getConfirmations());
        assertTrue(r.isSimulated());
    }

    @Test
    void equals_sameFields_shouldBeEqual() {
        TransactionResult a = TransactionResult.success("h", 1, true);
        TransactionResult b = TransactionResult.success("h", 1, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentFields_shouldNotBeEqual() {
        TransactionResult a = TransactionResult.success("h1", 1, true);
        TransactionResult b = TransactionResult.success("h2", 1, true);
        assertFalse(a.equals(b));
    }

    @Test
    void equals_nullAndOtherType_shouldReturnFalse() {
        TransactionResult a = new TransactionResult();
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
        assertTrue(a.equals(a));
    }

    @Test
    void toString_shouldContainStatus() {
        TransactionResult r = TransactionResult.success("0xabc", 1, false);
        String s = r.toString();
        assertNotNull(s);
        assertTrue(s.contains("SUCCESS"));
        assertTrue(s.contains("0xabc"));
    }

    @Test
    void statusEnum_shouldContainAllVariants() {
        assertEquals(5, TransactionResult.Status.values().length);
        assertEquals(TransactionResult.Status.SUCCESS,
                TransactionResult.Status.valueOf("SUCCESS"));
        assertEquals(TransactionResult.Status.PENDING_CONFIRMATION,
                TransactionResult.Status.valueOf("PENDING_CONFIRMATION"));
        assertEquals(TransactionResult.Status.AWAITING_APPROVAL,
                TransactionResult.Status.valueOf("AWAITING_APPROVAL"));
        assertEquals(TransactionResult.Status.EXECUTED_UNVERIFIED,
                TransactionResult.Status.valueOf("EXECUTED_UNVERIFIED"));
        assertEquals(TransactionResult.Status.FAILED,
                TransactionResult.Status.valueOf("FAILED"));
    }

    @Test
    void awaitingApproval_shouldCarryApprovalIdAndNotBeSuccess() {
        TransactionResult r = TransactionResult.awaitingApproval("ap-1");

        assertEquals(TransactionResult.Status.AWAITING_APPROVAL, r.getStatus());
        assertEquals("ap-1", r.getApprovalId());
        assertNull(r.getTxHash());
        assertNull(r.getError());
        assertFalse(r.isSuccess());
    }

    @Test
    void executedUnverified_shouldNotBeSuccessAndCarryReason() {
        TransactionResult r = TransactionResult.executedUnverified("ap-2", "no txHash");

        assertEquals(TransactionResult.Status.EXECUTED_UNVERIFIED, r.getStatus());
        assertEquals("ap-2", r.getApprovalId());
        assertEquals("no txHash", r.getError());
        assertFalse(r.isSuccess());
    }

    @Test
    void equals_shouldIncludeApprovalId() {
        assertNotEquals(TransactionResult.awaitingApproval("ap-1"),
                TransactionResult.awaitingApproval("ap-2"));
    }
}