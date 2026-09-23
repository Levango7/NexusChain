package org.nexus.gateway.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OrderStateMachine 测试 — 验证所有新增转换规则和非法转换拒绝。
 */
class OrderStateMachineTest {

    // ==================== Step 2 新增转换规则 ====================

    @Test
    void payingToSubmittedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAYING, PaymentOrder.OrderStatus.SUBMITTED));
    }

    @Test
    void submittedToPaidIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.SUBMITTED, PaymentOrder.OrderStatus.PAID));
    }

    @Test
    void submittedToFailedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.SUBMITTED, PaymentOrder.OrderStatus.FAILED));
    }

    @Test
    void submittedToExpiredIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.SUBMITTED, PaymentOrder.OrderStatus.EXPIRED));
    }

    @Test
    void paidToReorgedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAID, PaymentOrder.OrderStatus.REORGED));
    }

    @Test
    void reorgedToPaidIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REORGED, PaymentOrder.OrderStatus.PAID));
    }

    @Test
    void reorgedToFailedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REORGED, PaymentOrder.OrderStatus.FAILED));
    }

    // ==================== 保留的现有转换规则 ====================

    @Test
    void pendingToPayingIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.PAYING));
    }

    @Test
    void pendingToExpiredIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.EXPIRED));
    }

    @Test
    void pendingToFailedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.FAILED));
    }

    @Test
    void payingToPaidIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAYING, PaymentOrder.OrderStatus.PAID));
    }

    @Test
    void payingToFailedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAYING, PaymentOrder.OrderStatus.FAILED));
    }

    @Test
    void payingToExpiredIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAYING, PaymentOrder.OrderStatus.EXPIRED));
    }

    @Test
    void paidToRefundPendingIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAID, PaymentOrder.OrderStatus.REFUND_PENDING));
    }

    @Test
    void paidToRefundedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAID, PaymentOrder.OrderStatus.REFUNDED));
    }

    @Test
    void refundPendingToRefundedIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REFUND_PENDING, PaymentOrder.OrderStatus.REFUNDED));
    }

    @Test
    void refundPendingToPaidIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REFUND_PENDING, PaymentOrder.OrderStatus.PAID));
    }

    @Test
    void failedToPendingIsAllowed() {
        assertTrue(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.FAILED, PaymentOrder.OrderStatus.PENDING));
    }

    // ==================== 非法转换拒绝 ====================

    @Test
    void pendingToSubmittedIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.SUBMITTED));
    }

    @Test
    void pendingToPaidIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.PAID));
    }

    @Test
    void pendingToReorgedIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.REORGED));
    }

    @Test
    void submittedToPayingIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.SUBMITTED, PaymentOrder.OrderStatus.PAYING));
    }

    @Test
    void submittedToReorgedIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.SUBMITTED, PaymentOrder.OrderStatus.REORGED));
    }

    @Test
    void submittedToRefundPendingIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.SUBMITTED, PaymentOrder.OrderStatus.REFUND_PENDING));
    }

    @Test
    void paidToPayingIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAID, PaymentOrder.OrderStatus.PAYING));
    }

    @Test
    void paidToSubmittedIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.PAID, PaymentOrder.OrderStatus.SUBMITTED));
    }

    @Test
    void reorgedToSubmittedIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REORGED, PaymentOrder.OrderStatus.SUBMITTED));
    }

    @Test
    void reorgedToRefundPendingIsRejected() {
        assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REORGED, PaymentOrder.OrderStatus.REFUND_PENDING));
    }

    @Test
    void expiredToAnyIsRejected() {
        for (PaymentOrder.OrderStatus target : PaymentOrder.OrderStatus.values()) {
            assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.EXPIRED, target),
                    "EXPIRED -> " + target + " 应被拒绝");
        }
    }

    @Test
    void refundedToAnyIsRejected() {
        for (PaymentOrder.OrderStatus target : PaymentOrder.OrderStatus.values()) {
            assertFalse(OrderStateMachine.canTransition(PaymentOrder.OrderStatus.REFUNDED, target),
                    "REFUNDED -> " + target + " 应被拒绝");
        }
    }

    // ==================== transition() 方法 ====================

    @Test
    void transitionSuccessUpdatesStatus() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(PaymentOrder.OrderStatus.PAYING);
        order.setOrderNo("TEST-001");

        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.SUBMITTED);

        assertEquals(PaymentOrder.OrderStatus.SUBMITTED, order.getStatus());
    }

    @Test
    void transitionIllegalThrowsIllegalStateException() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setOrderNo("TEST-002");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.SUBMITTED));
        assertTrue(ex.getMessage().contains("Illegal order transition"));
        assertTrue(ex.getMessage().contains("TEST-002"));
    }

    @Test
    void transitionReorgedToPaidSuccess() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(PaymentOrder.OrderStatus.REORGED);
        order.setOrderNo("TEST-003");

        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);

        assertEquals(PaymentOrder.OrderStatus.PAID, order.getStatus());
    }

    @Test
    void transitionPaidToReorgedSuccess() {
        PaymentOrder order = new PaymentOrder();
        order.setStatus(PaymentOrder.OrderStatus.PAID);
        order.setOrderNo("TEST-004");

        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REORGED);

        assertEquals(PaymentOrder.OrderStatus.REORGED, order.getStatus());
    }

    // ==================== validTargets() 方法 ====================

    @Test
    void validTargetsForSubmitted() {
        var targets = OrderStateMachine.validTargets(PaymentOrder.OrderStatus.SUBMITTED);
        assertEquals(3, targets.size());
        assertTrue(targets.contains(PaymentOrder.OrderStatus.PAID));
        assertTrue(targets.contains(PaymentOrder.OrderStatus.FAILED));
        assertTrue(targets.contains(PaymentOrder.OrderStatus.EXPIRED));
    }

    @Test
    void validTargetsForReorged() {
        var targets = OrderStateMachine.validTargets(PaymentOrder.OrderStatus.REORGED);
        assertEquals(2, targets.size());
        assertTrue(targets.contains(PaymentOrder.OrderStatus.PAID));
        assertTrue(targets.contains(PaymentOrder.OrderStatus.FAILED));
    }

    @Test
    void validTargetsForExpiredIsEmpty() {
        var targets = OrderStateMachine.validTargets(PaymentOrder.OrderStatus.EXPIRED);
        assertTrue(targets.isEmpty());
    }

    @Test
    void validTargetsForRefundedIsEmpty() {
        var targets = OrderStateMachine.validTargets(PaymentOrder.OrderStatus.REFUNDED);
        assertTrue(targets.isEmpty());
    }

    @Test
    void validTargetsReturnsUnmodifiableSet() {
        var targets = OrderStateMachine.validTargets(PaymentOrder.OrderStatus.PENDING);
        assertThrows(UnsupportedOperationException.class, () ->
                targets.add(PaymentOrder.OrderStatus.SUBMITTED));
    }
}