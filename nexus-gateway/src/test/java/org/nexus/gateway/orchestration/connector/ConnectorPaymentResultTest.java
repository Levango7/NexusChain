package org.nexus.gateway.orchestration.connector;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.FinalityStatus;
import org.nexus.gateway.model.SubmissionStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConnectorPaymentResult 测试 — 验证 ok()/fail() 工厂方法、链式方法和新字段。
 */
class ConnectorPaymentResultTest {

    // ==================== ok() 工厂方法 ====================

    @Test
    void okSetsSuccessTrue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        assertTrue(result.isSuccess());
    }

    @Test
    void okSetsConnectorPaymentId() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        assertEquals("pay-001", result.getConnectorPaymentId());
    }

    @Test
    void okSetsStatus() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        assertEquals(PaymentStatus.PROCESSING, result.getStatus());
    }

    @Test
    void okDefaultsSubmissionStatusToSubmitted() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        assertEquals(SubmissionStatus.SUBMITTED, result.getSubmissionStatus());
    }

    @Test
    void okDefaultsFinalityStatusToOptimistic() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        assertEquals(FinalityStatus.OPTIMISTIC, result.getFinalityStatus());
    }

    @Test
    void okWithTxHashSetsTransactionHash() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING, "0xabc");
        assertEquals("0xabc", result.getTransactionHash());
    }

    @Test
    void okWithTxHashStillDefaultsSubmissionAndFinality() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING, "0xabc");
        assertEquals(SubmissionStatus.SUBMITTED, result.getSubmissionStatus());
        assertEquals(FinalityStatus.OPTIMISTIC, result.getFinalityStatus());
    }

    // ==================== fail() 工厂方法 ====================

    @Test
    void failSetsSuccessFalse() {
        ConnectorPaymentResult result = ConnectorPaymentResult.fail("error-msg");
        assertFalse(result.isSuccess());
    }

    @Test
    void failSetsStatusToFailed() {
        ConnectorPaymentResult result = ConnectorPaymentResult.fail("error-msg");
        assertEquals(PaymentStatus.FAILED, result.getStatus());
    }

    @Test
    void failSetsErrorMessage() {
        ConnectorPaymentResult result = ConnectorPaymentResult.fail("error-msg");
        assertEquals("error-msg", result.getErrorMessage());
    }

    @Test
    void failSetsSubmissionStatusToRejected() {
        ConnectorPaymentResult result = ConnectorPaymentResult.fail("error-msg");
        assertEquals(SubmissionStatus.REJECTED, result.getSubmissionStatus());
    }

    @Test
    void failSetsFinalityStatusToUnknown() {
        ConnectorPaymentResult result = ConnectorPaymentResult.fail("error-msg");
        assertEquals(FinalityStatus.UNKNOWN, result.getFinalityStatus());
    }

    // ==================== 链式方法 ====================

    @Test
    void withSubmissionStatusSetsValue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING)
                .withSubmissionStatus(SubmissionStatus.INCLUDED);
        assertEquals(SubmissionStatus.INCLUDED, result.getSubmissionStatus());
    }

    @Test
    void withFinalityStatusSetsValue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING)
                .withFinalityStatus(FinalityStatus.FINALIZED);
        assertEquals(FinalityStatus.FINALIZED, result.getFinalityStatus());
    }

    @Test
    void withLatencyMsSetsValue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING)
                .withLatencyMs(150);
        assertEquals(150, result.getLatencyMs());
    }

    @Test
    void withCostBpsSetsValue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING)
                .withCostBps(50);
        assertEquals(50, result.getCostBps());
    }

    @Test
    void chainMultipleWithers() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING, "0xabc")
                .withSubmissionStatus(SubmissionStatus.INCLUDED)
                .withFinalityStatus(FinalityStatus.FINALIZING)
                .withLatencyMs(200)
                .withCostBps(30);

        assertEquals(SubmissionStatus.INCLUDED, result.getSubmissionStatus());
        assertEquals(FinalityStatus.FINALIZING, result.getFinalityStatus());
        assertEquals(200, result.getLatencyMs());
        assertEquals(30, result.getCostBps());
        assertEquals("0xabc", result.getTransactionHash());
    }

    // ==================== getter/setter ====================

    @Test
    void setSubmissionStatusUpdatesValue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        result.setSubmissionStatus(SubmissionStatus.REJECTED);
        assertEquals(SubmissionStatus.REJECTED, result.getSubmissionStatus());
    }

    @Test
    void setFinalityStatusUpdatesValue() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        result.setFinalityStatus(FinalityStatus.UNKNOWN);
        assertEquals(FinalityStatus.UNKNOWN, result.getFinalityStatus());
    }

    @Test
    void setFinalityStatusToNull() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        result.setFinalityStatus(null);
        assertNull(result.getFinalityStatus());
    }

    @Test
    void setSubmissionStatusToNull() {
        ConnectorPaymentResult result = ConnectorPaymentResult.ok("pay-001", PaymentStatus.PROCESSING);
        result.setSubmissionStatus(null);
        assertNull(result.getSubmissionStatus());
    }
}