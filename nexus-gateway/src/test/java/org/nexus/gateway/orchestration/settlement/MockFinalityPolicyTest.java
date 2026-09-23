package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.FinalityStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MockFinalityPolicy 测试 — 验证测试环境即时最终化策略。
 */
class MockFinalityPolicyTest {

    @Test
    void getNameReturnsMockInstant() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        assertEquals("mock-instant", policy.getName());
    }

    @Test
    void getThresholdReturnsOne() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        assertEquals(1, policy.getThreshold());
    }

    @Test
    void evaluateFinalityAlwaysReturnsFinalized() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.FINALIZED, result.status());
        assertEquals(1, result.confirmations());
        assertEquals(1, result.threshold());
        assertTrue(result.note().contains("mock instant finality"));
    }

    @Test
    void evaluateFinalityReturnsFinalizedForNullTxHash() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        FinalityService.FinalityInfo result = policy.evaluateFinality(null);

        assertEquals(FinalityStatus.FINALIZED, result.status());
    }

    @Test
    void evaluateFinalityReturnsFinalizedForEmptyTxHash() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        FinalityService.FinalityInfo result = policy.evaluateFinality("");

        assertEquals(FinalityStatus.FINALIZED, result.status());
    }

    @Test
    void evaluateFinalityReturnsConsistentResultsAcrossCalls() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        FinalityService.FinalityInfo r1 = policy.evaluateFinality("0x1");
        FinalityService.FinalityInfo r2 = policy.evaluateFinality("0x2");

        assertEquals(r1.status(), r2.status());
        assertEquals(r1.confirmations(), r2.confirmations());
        assertEquals(r1.threshold(), r2.threshold());
    }

    @Test
    void implementsFinalityPolicyInterface() {
        MockFinalityPolicy policy = new MockFinalityPolicy();
        assertInstanceOf(FinalityPolicy.class, policy);
    }
}