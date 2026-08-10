package org.nexus.oracle.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Proposal#transitionTo(ProposalState)} 与 {@link Proposal#initState(ProposalState)}
 * 状态转换合法性单元测试（GOV-P1-01）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>合法状态转换路径（PENDING→ACTIVE→PASSED→EXECUTED 等）</li>
 *   <li>非法状态转换抛出 {@link IllegalStateException}</li>
 *   <li>幂等转换（相同状态不抛异常）</li>
 *   <li>initState 仅允许从 null 设置 PENDING / ACTIVE</li>
 *   <li>setState 公开 setter 已移除（编译期保证）</li>
 * </ul>
 */
class ProposalStateTransitionTest {

    // ---------- 合法转换路径 ----------

    @Test
    void transitionTo_pendingToActive_shouldSucceed() {
        Proposal p = newPendingProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.ACTIVE));
        assertEquals(ProposalState.ACTIVE, p.getState());
    }

    @Test
    void transitionTo_activeToPassed_shouldSucceed() {
        Proposal p = newActiveProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.PASSED));
        assertEquals(ProposalState.PASSED, p.getState());
    }

    @Test
    void transitionTo_activeToRejected_shouldSucceed() {
        Proposal p = newActiveProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.REJECTED));
        assertEquals(ProposalState.REJECTED, p.getState());
    }

    @Test
    void transitionTo_passedToExecuted_shouldSucceed() {
        Proposal p = newPassedProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.EXECUTED));
        assertEquals(ProposalState.EXECUTED, p.getState());
    }

    @Test
    void transitionTo_passedToExecutionFailed_shouldSucceed() {
        Proposal p = newPassedProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.EXECUTION_FAILED));
        assertEquals(ProposalState.EXECUTION_FAILED, p.getState());
    }

    @Test
    void transitionTo_pendingToCanceled_shouldSucceed() {
        Proposal p = newPendingProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.CANCELED));
        assertEquals(ProposalState.CANCELED, p.getState());
    }

    @Test
    void transitionTo_activeToCanceled_shouldSucceed() {
        Proposal p = newActiveProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.CANCELED));
        assertEquals(ProposalState.CANCELED, p.getState());
    }

    @Test
    void transitionTo_passedToCanceled_shouldSucceed() {
        Proposal p = newPassedProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.CANCELED));
        assertEquals(ProposalState.CANCELED, p.getState());
    }

    @Test
    void transitionTo_rejectedToCanceled_shouldSucceed() {
        Proposal p = new Proposal();
        p.initState(ProposalState.ACTIVE);
        p.transitionTo(ProposalState.REJECTED);
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.CANCELED));
        assertEquals(ProposalState.CANCELED, p.getState());
    }

    @Test
    void transitionTo_executionFailedToPassed_shouldSucceedForRetry() {
        // EXECUTION_FAILED → PASSED 允许重试
        Proposal p = newPassedProposal();
        p.transitionTo(ProposalState.EXECUTION_FAILED);
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.PASSED));
        assertEquals(ProposalState.PASSED, p.getState());
    }

    @Test
    void transitionTo_executionFailedToExecuted_shouldSucceedForRetry() {
        // EXECUTION_FAILED → EXECUTED 允许重试成功
        Proposal p = newPassedProposal();
        p.transitionTo(ProposalState.EXECUTION_FAILED);
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.EXECUTED));
        assertEquals(ProposalState.EXECUTED, p.getState());
    }

    @Test
    void transitionTo_fullLifecycle_shouldSucceed() {
        // 完整生命周期：PENDING → ACTIVE → PASSED → EXECUTED
        Proposal p = newPendingProposal();
        p.transitionTo(ProposalState.ACTIVE);
        p.transitionTo(ProposalState.PASSED);
        p.transitionTo(ProposalState.EXECUTED);
        assertEquals(ProposalState.EXECUTED, p.getState());
    }

    @Test
    void transitionTo_fullLifecycleWithFailure_shouldSucceed() {
        // 完整生命周期（含失败）：PENDING → ACTIVE → PASSED → EXECUTION_FAILED → PASSED → EXECUTED
        Proposal p = newPendingProposal();
        p.transitionTo(ProposalState.ACTIVE);
        p.transitionTo(ProposalState.PASSED);
        p.transitionTo(ProposalState.EXECUTION_FAILED);
        p.transitionTo(ProposalState.PASSED); // 重试
        p.transitionTo(ProposalState.EXECUTED);
        assertEquals(ProposalState.EXECUTED, p.getState());
    }

    // ---------- 非法转换 ----------

    @Test
    void transitionTo_pendingToExecuted_shouldThrow() {
        Proposal p = newPendingProposal();
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.EXECUTED));
    }

    @Test
    void transitionTo_pendingToPassed_shouldThrow() {
        Proposal p = newPendingProposal();
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.PASSED));
    }

    @Test
    void transitionTo_pendingToRejected_shouldThrow() {
        Proposal p = newPendingProposal();
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.REJECTED));
    }

    @Test
    void transitionTo_activeToExecuted_shouldThrow() {
        Proposal p = newActiveProposal();
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.EXECUTED));
    }

    @Test
    void transitionTo_executedToPassed_shouldThrow() {
        // EXECUTED 是终态，不能再转换
        Proposal p = newPassedProposal();
        p.transitionTo(ProposalState.EXECUTED);
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.PASSED));
    }

    @Test
    void transitionTo_executedToExecutionFailed_shouldThrow() {
        // EXECUTED 是终态，不能再转换
        Proposal p = newPassedProposal();
        p.transitionTo(ProposalState.EXECUTED);
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.EXECUTION_FAILED));
    }

    @Test
    void transitionTo_canceledToAny_shouldThrow() {
        // CANCELED 是终态
        Proposal p = newPendingProposal();
        p.transitionTo(ProposalState.CANCELED);
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.ACTIVE));
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.PASSED));
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.EXECUTED));
    }

    @Test
    void transitionTo_rejectedToPassed_shouldThrow() {
        Proposal p = newActiveProposal();
        p.transitionTo(ProposalState.REJECTED);
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.PASSED));
    }

    @Test
    void transitionTo_rejectedToExecuted_shouldThrow() {
        Proposal p = newActiveProposal();
        p.transitionTo(ProposalState.REJECTED);
        assertThrows(IllegalStateException.class, () -> p.transitionTo(ProposalState.EXECUTED));
    }

    // ---------- 幂等转换 ----------

    @Test
    void transitionTo_sameState_shouldBeIdempotent() {
        Proposal p = newActiveProposal();
        // 相同状态转换不抛异常
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.ACTIVE));
        assertEquals(ProposalState.ACTIVE, p.getState());
    }

    @Test
    void transitionTo_passedToPassed_shouldBeIdempotent() {
        Proposal p = newPassedProposal();
        assertDoesNotThrow(() -> p.transitionTo(ProposalState.PASSED));
        assertEquals(ProposalState.PASSED, p.getState());
    }

    // ---------- null 参数 ----------

    @Test
    void transitionTo_nullNewState_shouldThrow() {
        Proposal p = newActiveProposal();
        assertThrows(IllegalArgumentException.class, () -> p.transitionTo(null));
    }

    // ---------- initState ----------

    @Test
    void initState_fromNull_shouldSucceed() {
        Proposal p = new Proposal();
        assertDoesNotThrow(() -> p.initState(ProposalState.PENDING));
        assertEquals(ProposalState.PENDING, p.getState());
    }

    @Test
    void initState_fromNull_activeShouldSucceed() {
        Proposal p = new Proposal();
        assertDoesNotThrow(() -> p.initState(ProposalState.ACTIVE));
        assertEquals(ProposalState.ACTIVE, p.getState());
    }

    @Test
    void initState_alreadySet_shouldThrow() {
        Proposal p = newActiveProposal();
        assertThrows(IllegalStateException.class, () -> p.initState(ProposalState.PENDING));
    }

    @Test
    void initState_null_shouldThrow() {
        Proposal p = new Proposal();
        assertThrows(IllegalArgumentException.class, () -> p.initState(null));
    }

    @Test
    void initState_passed_shouldThrow() {
        // initState 仅允许 PENDING 或 ACTIVE
        Proposal p = new Proposal();
        assertThrows(IllegalArgumentException.class, () -> p.initState(ProposalState.PASSED));
    }

    @Test
    void initState_executed_shouldThrow() {
        Proposal p = new Proposal();
        assertThrows(IllegalArgumentException.class, () -> p.initState(ProposalState.EXECUTED));
    }

    // ---------- 辅助方法 ----------

    private Proposal newPendingProposal() {
        Proposal p = new Proposal();
        p.initState(ProposalState.PENDING);
        return p;
    }

    private Proposal newActiveProposal() {
        Proposal p = new Proposal();
        p.initState(ProposalState.ACTIVE);
        return p;
    }

    private Proposal newPassedProposal() {
        Proposal p = newActiveProposal();
        p.transitionTo(ProposalState.PASSED);
        return p;
    }
}