package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.model.FinalityStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PspFinalityPolicy 测试 — 验证始终返回 FINALIZED 的 PSP 确认策略。
 */
class PspFinalityPolicyTest {

    @Test
    void getNameReturnsPspConfirm() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        assertEquals("psp-confirm", policy.getName());
    }

    @Test
    void getThresholdReturnsOne() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        assertEquals(1, policy.getThreshold());
    }

    @Test
    void evaluateFinalityAlwaysReturnsFinalized() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.FINALIZED, result.status());
        assertEquals(1, result.confirmations());
        assertEquals(1, result.threshold());
        assertTrue(result.note().contains("PSP confirmation"));
    }

    @Test
    void evaluateFinalityReturnsFinalizedForNullTxHash() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        FinalityService.FinalityInfo result = policy.evaluateFinality(null);

        assertEquals(FinalityStatus.FINALIZED, result.status());
    }

    @Test
    void evaluateFinalityReturnsFinalizedForEmptyTxHash() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        FinalityService.FinalityInfo result = policy.evaluateFinality("");

        assertEquals(FinalityStatus.FINALIZED, result.status());
    }

    @Test
    void evaluateFinalityReturnsConsistentResultsAcrossCalls() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        FinalityService.FinalityInfo r1 = policy.evaluateFinality("0x1");
        FinalityService.FinalityInfo r2 = policy.evaluateFinality("0x2");

        assertEquals(r1.status(), r2.status());
        assertEquals(r1.confirmations(), r2.confirmations());
        assertEquals(r1.threshold(), r2.threshold());
    }

    @Test
    void implementsFinalityPolicyInterface() {
        PspFinalityPolicy policy = new PspFinalityPolicy();
        assertInstanceOf(FinalityPolicy.class, policy);
    }
}